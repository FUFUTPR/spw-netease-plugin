package com.example.netease.svc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Map;
import javax.imageio.ImageIO;

/** A daily playlist image uses the host's real file/cache-revision contract. */
public final class DailyRecommendationCover {
    private DailyRecommendationCover() {}
    public static Map<String, Long> prepare(LocalDate day) throws Exception {
        Path file = PlaylistCover.hostFile(DailyRecommendations.HOST_ID);
        if (file == null) throw new IllegalStateException("找不到每日推荐封面目录");
        Path stamp = file.resolveSibling(file.getFileName() + ".daily-date");
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) >= 1024 && Files.isRegularFile(stamp)
                && Files.readString(stamp).equals(day.toString()))
            return Map.of(DailyRecommendations.HOST_ID, Files.getLastModifiedTime(file).toMillis());
        var image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setPaint(new GradientPaint(0, 0, new Color(0xEC3C57), 600, 600, new Color(0x9F244B)));
            graphics.fillRect(0, 0, 600, 600);
            graphics.setColor(new Color(255,255,255,40)); graphics.fillOval(280, -160, 500, 500);
            graphics.setColor(Color.WHITE); graphics.setStroke(new BasicStroke(14));
            graphics.drawRoundRect(92, 124, 416, 378, 42, 42); graphics.drawLine(96, 214, 504, 214);
            graphics.drawLine(194, 96, 194, 154); graphics.drawLine(406, 96, 406, 154);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 194));
            String date = Integer.toString(day.getDayOfMonth());
            graphics.drawString(date, (600-graphics.getFontMetrics().stringWidth(date))/2, 421);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            String label = "DAILY MIX  /  " + day.getMonthValue();
            graphics.drawString(label, (600-graphics.getFontMetrics().stringWidth(label))/2, 554);
        } finally { graphics.dispose(); }
        Path temporary = Files.createTempFile(file.getParent(), "daily-cover-", ".png");
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) throw new IllegalStateException("无法编码每日推荐封面");
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(stamp, day.toString(), StandardCharsets.UTF_8);
        } finally { Files.deleteIfExists(temporary); }
        return Map.of(DailyRecommendations.HOST_ID, Files.getLastModifiedTime(file).toMillis());
    }
}
