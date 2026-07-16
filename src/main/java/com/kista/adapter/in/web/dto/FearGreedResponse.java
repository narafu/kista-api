package com.kista.adapter.in.web.dto;

import com.kista.domain.model.market.FearGreedSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// CNN·크립토 두 소스를 한 번에 내려주는 번들 응답
public record FearGreedResponse(
        @Schema(description = "CNN 공포탐욕지수 현재값 + 추이 이력")
        SourceView cnn,
        @Schema(description = "크립토 공포탐욕지수 현재값 + 추이 이력")
        SourceView crypto) {

    // 소스별 현재값 + 추이 이력
    public record SourceView(
            @Schema(description = "최신 시점 값 (이력이 없으면 null)")
            Point current,
            @Schema(description = "추이 이력 (오름차순 정렬)")
            List<Point> history) {}

    // 단일 시점 — date(ISO-8601 Instant), value(0~100), rating(enum 이름)
    public record Point(
            @Schema(description = "기록 시각 (ISO-8601)")
            String date,
            @Schema(description = "지수 값 (0~100)", example = "50")
            int value,
            @Schema(description = "등급", example = "NEUTRAL")
            String rating) {}

    public static FearGreedResponse from(List<FearGreedSnapshot> cnn, List<FearGreedSnapshot> crypto) {
        return new FearGreedResponse(toView(cnn), toView(crypto));
    }

    private static SourceView toView(List<FearGreedSnapshot> snapshots) {
        List<Point> history = snapshots.stream().map(FearGreedResponse::toPoint).toList();
        // 오름차순이므로 마지막이 최신 — 비어있으면 current=null
        Point current = history.isEmpty() ? null : history.get(history.size() - 1);
        return new SourceView(current, history);
    }

    private static Point toPoint(FearGreedSnapshot s) {
        return new Point(s.snapshotDate().toString(), s.value(), s.rating().name());
    }
}
