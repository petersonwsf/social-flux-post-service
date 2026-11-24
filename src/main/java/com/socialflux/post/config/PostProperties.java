package com.socialflux.post.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Toda a configuração do serviço, vinda de variáveis de ambiente (ver application.yml). Falha rápida se inválida. */
@Validated
@ConfigurationProperties(prefix = "post")
public record PostProperties(
        @Valid @NotNull Security security,
        @DefaultValue List<String> allowedOrigins,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Media media,
        @Valid @NotNull Moderation moderation,
        @Valid @NotNull Maintenance maintenance,
        @Valid @NotNull Reconciler reconciler) {

    public record Security(
            @NotBlank String jwksUri,
            @NotBlank String issuer,
            @NotBlank String audience,
            @DefaultValue("PT60S") Duration clockSkew) {
    }

    /** D10: texto ≤ 1.000 caracteres (o banco não aceita mais que isso), 60 postagens/h e 600 curtidas/h por usuário. */
    public record Limits(
            @Min(1) @Max(1000) int maxBody,
            @Min(1) int postsPerHour,
            @Min(1) int likesPerHour) {
    }

    /** Bucket das imagens de postagem e o tempo de carência antes de um objeto sem postagem ser considerado órfão. */
    public record Media(@NotBlank String bucket, @NotNull Duration orphanGrace) {
    }

    /**
     * Moderação de conteúdo (API de moderação da OpenAI). Desligada, tudo passa. {@code apiKey} é obrigatória quando ligada
     * (conferido ao subir). {@code failClosedText}/{@code failClosedImages}: se o provedor falhar,
     * recusa o texto/as imagens (503) em vez de deixá-los passar.
     */
    public record Moderation(boolean enabled, String apiKey, @NotBlank String baseUrl, @NotBlank String model,
                             @NotNull Duration timeout, boolean failClosedText, boolean failClosedImages,
                             @Valid @NotNull Thresholds thresholds) {
    }

    /** Scores (0 a 1) a partir dos quais cada categoria bloqueia. Ofensa comum ({@code harassment}) nunca bloqueia. */
    public record Thresholds(
            @DecimalMin("0") @DecimalMax("1") double hate,
            @DecimalMin("0") @DecimalMax("1") double hateThreatening,
            @DecimalMin("0") @DecimalMax("1") double harassmentThreatening,
            @DecimalMin("0") @DecimalMax("1") double sexual,
            @DecimalMin("0") @DecimalMax("1") double selfHarm,
            @DecimalMin("0") @DecimalMax("1") double selfHarmImage,
            @DecimalMin("0") @DecimalMax("1") double violenceGraphic) {
    }

    /** Rotinas agendadas: poda de túmulos, varredura de órfãos e limpeza de contas excluídas. */
    public record Maintenance(boolean enabled, @NotNull Duration interval, @NotNull Duration tombstoneRetention,
                              @Min(1) int purgeBatchSize) {
    }

    /** Rotina que confere {@code likes_count}/{@code replies_count} contra as linhas reais e corrige as divergências. */
    public record Reconciler(boolean enabled, @NotNull Duration interval, @Min(1) int batchSize) {
    }
}
