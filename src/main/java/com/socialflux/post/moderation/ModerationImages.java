package com.socialflux.post.moderation;

import com.socialflux.media.ProcessedMedia;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Prepara a mídia já processada para análise. Imagens estáticas (JPEG/PNG re-codificados) vão como estão. GIF vai pelo
 * primeiro quadro, em JPEG: o provedor não aceita GIF animado e a análise dos demais quadros fica para depois.
 */
final class ModerationImages {

    private ModerationImages() {
    }

    static ModerationImage prepare(ProcessedMedia media) {
        if (media.kind() != ProcessedMedia.Kind.GIF) {
            return new ModerationImage(media.bytes(), media.contentType());
        }
        return new ModerationImage(firstFrameAsJpeg(media.bytes()), ProcessedMedia.JPEG);
    }

    private static byte[] firstFrameAsJpeg(byte[] gif) {
        try {
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(gif));
            if (frame == null) {
                throw new ModerationUnavailableException("Não foi possível ler o primeiro quadro do GIF", null);
            }
            BufferedImage rgb = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);                  // GIF pode ter transparência; JPEG não
                graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                graphics.drawImage(frame, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "jpeg", out)) {
                throw new ModerationUnavailableException("Não foi possível codificar o quadro do GIF", null);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ModerationUnavailableException("Falha ao ler o GIF para moderação", e);
        }
    }
}
