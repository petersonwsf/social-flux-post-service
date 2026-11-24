package com.socialflux.post.service;

import com.socialflux.media.MediaKeys;
import com.socialflux.media.MediaProcessor;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.post.config.PostProperties;
import com.socialflux.post.domain.PostRepository;
import com.socialflux.post.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mídia das postagens (D8, D9, D16): processa o anexo com a biblioteca (imagem re-codificada ou GIF regravado), grava no
 * bucket com chave gerada no servidor, entrega só para quem pode ver a postagem e apaga objetos depois do commit ou quando
 * ficam órfãos. O navegador nunca fala com o RustFS.
 */
@Service
public class PostMediaService {

    private static final Logger log = LoggerFactory.getLogger(PostMediaService.class);
    private static final int SWEEP_BATCH = 500;
    /** Máximo de imagens por postagem. */
    public static final int MAX_IMAGES = 5;

    /** Anexo já processado e gravado. */
    public record Stored(String key, ProcessedMedia media) {
    }

    /** Resultado da entrega: 404 ({@code empty}), 304 ({@code notModified}) ou os bytes. */
    public record Delivery(boolean notModified, String key, byte[] bytes, String contentType) {
    }

    private final MediaProcessor processor;
    private final MediaStorage storage;
    private final PostRepository posts;
    private final String bucket;
    private final PostProperties.Media properties;
    private final Clock clock;

    public PostMediaService(MediaProcessor processor, MediaStorage storage, PostRepository posts,
                            PostProperties properties, Clock clock) {
        this.processor = processor;
        this.storage = storage;
        this.posts = posts;
        this.properties = properties.media();
        this.bucket = this.properties.bucket();
        this.clock = clock;
    }

    /** Processa FORA de transação (decodificar é caro) e grava o objeto. Recusas saem como {@code MediaException}. */
    public Stored store(byte[] upload) {
        ProcessedMedia media = processor.process(upload);
        String key = MediaKeys.newKey(MediaKeys.POST_PREFIX, media.extension(), clock);
        storage.put(bucket, key, media.bytes(), media.contentType());
        return new Stored(key, media);
    }

    /**
     * Processa TODOS os anexos antes de gravar qualquer um (uma recusa não deixa objeto para trás) e confere as regras do
     * conjunto: até {@code MAX_IMAGES} e GIF só sozinho. Se gravar um falhar, apaga os já gravados.
     */
    public List<Stored> storeAll(List<byte[]> uploads) {
        if (uploads.size() > MAX_IMAGES) {
            throw ApiException.unprocessable("imagens_demais", "Envie no máximo " + MAX_IMAGES + " imagens por postagem");
        }
        List<ProcessedMedia> processed = uploads.stream().map(processor::process).toList();
        if (processed.size() > 1 && processed.stream().anyMatch(m -> m.kind() == ProcessedMedia.Kind.GIF)) {
            throw ApiException.unprocessable("gif_deve_ficar_sozinho", "Um GIF não pode ser enviado junto com outras imagens");
        }
        List<Stored> stored = new ArrayList<>();
        try {
            for (ProcessedMedia media : processed) {
                String key = MediaKeys.newKey(MediaKeys.POST_PREFIX, media.extension(), clock);
                storage.put(bucket, key, media.bytes(), media.contentType());
                stored.add(new Stored(key, media));
            }
        } catch (RuntimeException e) {
            stored.forEach(s -> deleteQuietly(s.key()));
            throw e;
        }
        return stored;
    }

    /** Compensação: apaga um objeto que acabou sem postagem (a varredura de órfãos cobre se isto falhar). */
    public void deleteQuietly(String key) {
        try {
            storage.delete(bucket, key);
        } catch (RuntimeException e) {
            log.warn("Não foi possível remover a imagem {} (a varredura de órfãos tenta depois): {}", key, e.toString());
        }
    }

    /** Agenda a remoção dos objetos para depois do commit (ou já, se não houver transação). Falhas só geram log. */
    public void deleteAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Runnable action = () -> keys.forEach(this::deleteQuietly);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Entrega a imagem (I): chave no formato do servidor → postagem dona → visibilidade para {@code viewer} → bytes. Chave
     * desconhecida e postagem invisível respondem igual (vazio = 404). Com {@code If-None-Match} igual à chave, devolve 304
     * sem ler o objeto (a visibilidade foi conferida do mesmo jeito).
     */
    public Optional<Delivery> deliver(UUID viewer, String key, String ifNoneMatch) {
        if (!MediaKeys.isValid(MediaKeys.POST_PREFIX, key)) {
            return Optional.empty();
        }
        Optional<PostRepository.MediaRef> ref = posts.findMedia(viewer, key).filter(PostRepository.MediaRef::visible);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        if (ifNoneMatch != null && matches(ifNoneMatch, key)) {
            return Optional.of(new Delivery(true, key, null, MediaKeys.contentTypeOf(key)));
        }
        return storage.get(bucket, key)
                .map(object -> new Delivery(false, key, object.bytes(), MediaKeys.contentTypeOf(key)));
    }

    /** Varredura de órfãos (E2): objetos com mais de {@code orphan-grace} cuja chave não está em nenhuma postagem. */
    public int sweepOrphans() {
        Instant limit = clock.instant().minus(properties.orphanGrace());
        List<String> candidates = storage.list(bucket, MediaKeys.POST_PREFIX + "_").stream()
                .filter(k -> k.lastModified() == null || k.lastModified().isBefore(limit))
                .map(MediaStorage.StoredKey::key)
                .toList();
        int removed = 0;
        for (int i = 0; i < candidates.size(); i += SWEEP_BATCH) {
            List<String> batch = candidates.subList(i, Math.min(candidates.size(), i + SWEEP_BATCH));
            Set<String> inUse = new HashSet<>(posts.existingImageKeys(batch));
            for (String key : batch) {
                if (!inUse.contains(key)) {
                    deleteQuietly(key);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Varredura de mídia: {} objeto(s) órfão(s) removido(s)", removed);
        }
        return removed;
    }

    private static boolean matches(String ifNoneMatch, String key) {
        for (String tag : ifNoneMatch.split(",")) {
            String t = tag.trim();
            if (t.startsWith("W/")) {
                t = t.substring(2);
            }
            if (t.equals("\"" + key + "\"") || t.equals("*")) {
                return true;
            }
        }
        return false;
    }
}
