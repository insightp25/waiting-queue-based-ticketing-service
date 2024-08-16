package io.clean.tdd.hp12.domain.data;

import io.clean.tdd.hp12.domain.reservation.model.Reservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataPlatformService {

    public void sendReservationData(Reservation reservation) {
        log.debug("Mocking a client sending reservation data to an external data platform.");
    }
}
