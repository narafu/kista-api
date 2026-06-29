package com.kista.adapter.in.web.dto;

import com.kista.domain.model.market.FearGreedSnapshot;

import java.util.List;

// CNN·크립토 두 소스를 한 번에 내려주는 번들 응답
public record FearGreedResponse(SourceView cnn, SourceView crypto) {

    // 소스별 현재값 + 추이 이력
    public record SourceView(Point current, List<Point> history) {}

    // 단일 시점 — date(ISO-8601 Instant), value(0~100), rating(enum 이름)
    public record Point(String date, int value, String rating) {}

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
