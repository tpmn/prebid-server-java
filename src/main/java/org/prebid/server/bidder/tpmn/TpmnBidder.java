package org.prebid.server.bidder.tpmn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.iab.openrtb.request.*;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.*;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.tpmn.ExtImpTpmn;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.util.ObjectUtil;

import java.math.BigDecimal;
import java.util.*;

public class TpmnBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpTpmn>> TPMN_EXT_TYPE_REFERENCE = new TypeReference<>() {};
    private static final String BIDDER_CURRENCY = "USD";

    private final String endpointUrl;
    private final JacksonMapper mapper;
    private final CurrencyConversionService currencyConversionService;

    public TpmnBidder(String endpointUrl, JacksonMapper mapper, CurrencyConversionService currencyConversionService) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
        this.currencyConversionService = Objects.requireNonNull(currencyConversionService);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<Imp> validImps = new ArrayList<>();
        final List<BidderError> errors = new ArrayList<>();
        for (Imp imp : request.getImp()) {
            try {
                final ExtImpTpmn extImpTpmn = parseImpExt(imp);
                final Imp updatedImp = modifyImp(imp, extImpTpmn, request);
                if (updatedImp != null) validImps.add(updatedImp);
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
                continue;
            }
        }

        final BidRequest outgoingRequest = request.toBuilder().imp(validImps).build();
        return Result.of(Collections.singletonList(BidderUtil.defaultRequest(outgoingRequest, endpointUrl, mapper)), errors);

    }

    private ExtImpTpmn parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), TPMN_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }
    }

    private Imp modifyImp(Imp imp, ExtImpTpmn extImpTpmn, BidRequest request) {
        Integer inventoryId = extImpTpmn.getInventoryId();
        final Imp.ImpBuilder impBuilder = imp.toBuilder().tagid(String.valueOf(inventoryId));
        final String impId = imp.getId();
        final Price resolvedBidFloor = resolveBidFloor(imp, request);

        if (imp.getBanner() != null) {
            impBuilder.id(impId).banner(modifyBanner(imp.getBanner())).video(null).xNative(null);
        } else if (imp.getVideo() != null) {
            impBuilder.id(impId).xNative(null);
        } else if (imp.getXNative() != null) {
            impBuilder.id(impId).xNative(modifyNative(imp.getXNative()));
        } else {
            return null;
        }

        return impBuilder
                .bidfloor(resolvedBidFloor.getValue())
                .bidfloorcur(resolvedBidFloor.getCurrency())
                .ext(mapper.mapper().valueToTree(extImpTpmn))
                .build();
    }




    private static Banner modifyBanner(Banner banner) {
        final Integer w = banner.getW();
        final Integer h = banner.getH();
        final List<Format> formats = banner.getFormat();

        if (w == null || w == 0 || h == null || h == 0) {
            if (CollectionUtils.isNotEmpty(formats)) {
                final Format firstFormat = formats.get(0);
                return banner.toBuilder()
                        .w(firstFormat.getW())
                        .h(firstFormat.getH())
                        .build();
            }

            throw new PreBidException("Size information missing for banner");
        }

        return banner;
    }

    private Native modifyNative(Native xNative) {
        final JsonNode requestNode;
        try {
            requestNode = mapper.mapper().readTree(xNative.getRequest());
        } catch (JsonProcessingException e) {
            throw new PreBidException(e.getMessage());
        }

        final JsonNode nativeNode = requestNode != null
                ? requestNode.path("native")
                : MissingNode.getInstance();

        if (nativeNode.isMissingNode()) {
            final JsonNode modifiedRequestNode = mapper.mapper().createObjectNode().set("native", requestNode);
            return xNative.toBuilder()
                    .request(mapper.encodeToString(modifiedRequestNode))
                    .build();
        }

        return xNative;
    }


    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {

        final List<BidderError> errors = new ArrayList<>();
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.withValues(extractBids(bidRequest, bidResponse, errors));
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidRequest bidRequest,
                                        BidResponse bidResponse,
                                        List<BidderError> errors) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Collections.emptyList();
        }

        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(TpmnBidder::isValidBid)
                .map(bid -> createBidderBid(bid, bidRequest.getImp(), bidResponse.getCur(), errors))
                .toList();
    }

    private static boolean isValidBid(Bid bid) {
        return BidderUtil.isValidPrice(ObjectUtil.getIfNotNull(bid, Bid::getPrice));
    }

    private static BidderBid createBidderBid(Bid bid, List<Imp> imps, String currency, List<BidderError> errors) {
        if (StringUtils.isNotEmpty(currency)) {
            currency = BIDDER_CURRENCY;
        }
        final BidType bidType = getBidType(bid, imps);
        if (bidType == null) {
            errors.add(BidderError.badServerResponse(
                    "ignoring bid id=%s, request doesn't contain any valid impression with id=%s"
                            .formatted(bid.getId(), bid.getImpid())));

            return null;
        }

        return BidderBid.of(bid, bidType, currency);
    }


    private static BidType getBidType(Bid bid, List<Imp> imps) {
        final String impId = bid.getImpid();
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                } else if (imp.getXNative() != null) {
                    return BidType.xNative;
                }
            }
        }
        throw new PreBidException("Failed to find native/banner/video impression " + impId);
    }


    private Price resolveBidFloor(Imp imp, BidRequest bidRequest) {
        final Price initialBidFloorPrice = Price.of(imp.getBidfloorcur(), imp.getBidfloor());
        return BidderUtil.shouldConvertBidFloor(initialBidFloorPrice, BIDDER_CURRENCY)
                ? convertBidFloor(initialBidFloorPrice, bidRequest)
                : initialBidFloorPrice;
    }

    private Price convertBidFloor(Price bidFloorPrice, BidRequest bidRequest) {
        final BigDecimal convertedPrice = currencyConversionService.convertCurrency(
                bidFloorPrice.getValue(),
                bidRequest,
                bidFloorPrice.getCurrency(),
                BIDDER_CURRENCY);

        return Price.of(BIDDER_CURRENCY, convertedPrice);
    }

}

