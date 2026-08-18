package com.hq.backend.place.dto;

import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.PlaceType;
import com.hq.backend.place.UserPlace;
import java.util.UUID;

public record PlaceResponse(
        UUID placeId,
        PlaceType placeType,
        String placeName,
        String address,
        double lat,
        double lng,
        boolean isPrimary
) {

    public static PlaceResponse from(UserPlace place, PlaceCoordinateCodec codec) {
        return new PlaceResponse(
                place.getPlaceId(),
                PlaceType.valueOf(place.getPlaceType().toUpperCase()),
                place.getPlaceName(),
                place.getAddress(),
                codec.decode(place.getLatEnc()),
                codec.decode(place.getLngEnc()),
                place.isPrimary());
    }
}
