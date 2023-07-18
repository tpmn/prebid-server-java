package org.prebid.server.proto.openrtb.ext.request.tpmn;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor(staticName = "of")
public class ExtImpTpmn {

    @JsonProperty("inventoryId")
    Integer inventoryId;

}
