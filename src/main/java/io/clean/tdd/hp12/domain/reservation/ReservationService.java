package io.clean.tdd.hp12.domain.reservation;

import io.clean.tdd.hp12.domain.concert.model.Seat;
import io.clean.tdd.hp12.domain.concert.port.SeatRepository;
import io.clean.tdd.hp12.domain.point.model.Point;
import io.clean.tdd.hp12.domain.point.model.PointHistory;
import io.clean.tdd.hp12.domain.point.port.PointHistoryRepository;
import io.clean.tdd.hp12.domain.point.port.PointRepository;
import io.clean.tdd.hp12.domain.queue.model.WaitingQueue;
import io.clean.tdd.hp12.domain.queue.port.WaitingQueueRepository;
import io.clean.tdd.hp12.domain.reservation.model.Payment;
import io.clean.tdd.hp12.domain.reservation.model.Reservation;
import io.clean.tdd.hp12.domain.reservation.port.PaymentRepository;
import io.clean.tdd.hp12.domain.reservation.port.ReservationRepository;
import io.clean.tdd.hp12.domain.user.model.User;
import io.clean.tdd.hp12.domain.user.port.UserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final PointRepository pointRepository;
    private final PaymentRepository paymentRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final ReservationRepository reservationRepository;
    private final WaitingQueueRepository waitingQueueRepository;

    public List<Reservation> hold(long userId, long concertId, List<Integer> seatNumbers) {
        //1. seat: 예약을 희망하는 좌석(들)을 임시 점유 처리한다
        List<Seat> seats = seatNumbers.stream()
            .map(seatNumber -> seatRepository.findByConcertIdAndSeatNumber(concertId, seatNumber))
            .toList();
        seats.forEach(Seat::validateAvailabile);
        List<Seat> seatsOnHold = seats.stream()
            .map(Seat::hold)
            .map(seatRepository::update)
            .toList();

        //2. 결제 정보를 생성후 저장한다
        User user = userRepository.getById(userId);
        long dueAmount = Payment.calculateAmount(seatsOnHold);
        Payment payment = Payment.issuePayment(user, dueAmount);
        paymentRepository.save(payment);

        //3. 임시 예약하는 현재 시점에서 대기열의 만료 시간을 정책시간 만큼 업데이트 한다.
        WaitingQueue token = waitingQueueRepository.findByUserId(user.id());
        WaitingQueue refreshedToken = token.refreshForPayment();
        waitingQueueRepository.update(refreshedToken);

        //4. 임시 예약 정보를 생성후 저장한다
        List<Reservation> reservations = Reservation.hold(seatsOnHold, user, payment);
        reservations.forEach(reservationRepository::save);

        //5. 임시 예약 정보를 반환한다
        return reservations;
    }

    public List<Reservation> finalize(long userId, long paymentId, String accessKey) {
        //1. point: 유저의 현재 포인트 잔액을 조회한다 -> 부족할시 에러를 던진다
        Point point = pointRepository.getByUserId(userId);
        Payment payment = paymentRepository.findById(paymentId);
        point.validateSufficient(payment.amount());

        //2. point: 포인트를 결제금액만큼 사용한다(+사용내역을 추가한다)
        Point deductedPoint = pointRepository.save(point.use(payment.amount()));
        pointHistoryRepository.save(PointHistory.generateUseTypeOf(deductedPoint.user(), payment.amount()));

        //3. reservation: 예약의 상태를 완료로 변경후 저장한다
        List<Reservation> finalizedReservations = reservationRepository.findByPaymentId(paymentId).stream()
            .map(Reservation::finalizeStatus)
            .map(reservationRepository::save)
            .toList();

        //4. seat: 좌석의 상태를 완료로 변경한다
        finalizedReservations.stream()
            .map(Reservation::seat)
            .map(Seat::close)
            .forEach(seatRepository::update);

        //5. waiting queue: 대기 토큰을 만료한다
        WaitingQueue expiredToken = waitingQueueRepository.getByAccessKey(accessKey).expire();
        waitingQueueRepository.update(expiredToken);

        //6. 완료된 예약 정보를 반환한다
        return finalizedReservations;
    }

    public void bulkExpireTimedOutReservations() {
        reservationRepository.bulkExpireTimedOutReservations(LocalDateTime.now().truncatedTo(
            ChronoUnit.SECONDS));
    }
}
