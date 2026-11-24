package com.socialflux.post.moderation;

import com.socialflux.media.ProcessedMedia;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Preparo da mídia para análise: imagem estática vai como está; GIF vai pelo primeiro quadro, em JPEG. */
class ModerationImagesTest {

    @Test
    void staticImageIsSentAsIs() {
        byte[] bytes = {1, 2, 3};
        ModerationImage image = ModerationImages.prepare(
                new ProcessedMedia(bytes, ProcessedMedia.PNG, "png", 10, 10, ProcessedMedia.Kind.IMAGE));
        assertThat(image.bytes()).isSameAs(bytes);
        assertThat(image.contentType()).isEqualTo(ProcessedMedia.PNG);
    }

    @Test
    void gifIsSentAsTheFirstFrameInJpeg() throws Exception {
        BufferedImage frame = new BufferedImage(40, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 24);
        g.dispose();
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(frame, "gif", gif);

        ModerationImage image = ModerationImages.prepare(
                new ProcessedMedia(gif.toByteArray(), ProcessedMedia.GIF_TYPE, "gif", 40, 24, ProcessedMedia.Kind.GIF));

        assertThat(image.contentType()).isEqualTo(ProcessedMedia.JPEG);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(24);
    }

    @Test
    void unreadableGifIsReportedAsUnavailable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ModerationImages.prepare(
                        new ProcessedMedia(new byte[]{1, 2, 3}, ProcessedMedia.GIF_TYPE, "gif", 1, 1, ProcessedMedia.Kind.GIF)))
                .isInstanceOf(ModerationUnavailableException.class);
    }
}
