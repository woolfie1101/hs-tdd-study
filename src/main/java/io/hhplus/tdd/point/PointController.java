package io.hhplus.tdd.point;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/point")
public class PointController {

    private static final Logger log = LoggerFactory.getLogger(PointController.class);

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    /**
     * TODO - 특정 유저의 포인트를 조회하는 기능을 작성해주세요.
     */
    @GetMapping("{id}")
    public ResponseEntity<UserPoint> point(
        @PathVariable long id
    ) {
        log.info("Retrieving points for user {}", id);
        UserPoint userPoint = pointService.getPoint(id);
        return ResponseEntity.ok(userPoint);
    }

    /**
     * TODO - 특정 유저의 포인트 충전/이용 내역을 조회하는 기능을 작성해주세요.
     */
    @GetMapping("{id}/histories")
    public ResponseEntity<List<PointHistory>> history(
        @PathVariable long id
    ) {
        log.info("Retrieving point history for user {}", id);
        List<PointHistory> histories = pointService.getPointHistory(id);
        return ResponseEntity.ok(histories);
    }

    /**
     * TODO - 특정 유저의 포인트를 충전하는 기능을 작성해주세요.
     */
    @PatchMapping("{id}/charge")
    public ResponseEntity<UserPoint> charge(
        @PathVariable long id,
        @RequestBody long amount
    ) {
        log.info("Charging {} points to user {}", amount, id);
        UserPoint userPoint = pointService.chargePoint(id, amount);
        return ResponseEntity.ok(userPoint);
    }

    /**
     * TODO - 특정 유저의 포인트를 사용하는 기능을 작성해주세요.
     */
    @PatchMapping("{id}/use")
    public ResponseEntity<UserPoint> use(
        @PathVariable long id,
        @RequestBody long amount
    ) {
        log.info("Using {} points from user {}", amount, id);
        UserPoint userPoint = pointService.usePoint(id, amount);
        return ResponseEntity.ok(userPoint);
    }
}
