package com.smartparking.modules.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * Outbound e-mail port. No real provider is wired: DISABLED (default) drops the message and reports NOT_CONFIGURED;
 * LOG writes it to the application log and is only acceptable on a developer machine.
 */
public interface MailGateway {
    enum Result { SENT, LOGGED_FOR_DEVELOPMENT, NOT_CONFIGURED }
    Result send(String to, String subject, String body);
    String mode();

    @Configuration
    class Config {
        private static final Logger log = LoggerFactory.getLogger(MailGateway.class);
        @Bean @ConditionalOnMissingBean(MailGateway.class)
        MailGateway mailGateway(@Value("${app.mail.mode:DISABLED}") String mode) {
            if ("LOG".equalsIgnoreCase(mode)) {
                return new MailGateway() {
                    public Result send(String to, String subject, String body) {
                        log.warn("[MAIL_MODE=LOG solo desarrollo] para={} asunto={}\n{}", to, subject, body);
                        return Result.LOGGED_FOR_DEVELOPMENT;
                    }
                    public String mode() { return "LOG"; }
                };
            }
            return new MailGateway() {
                public Result send(String to, String subject, String body) { return Result.NOT_CONFIGURED; }
                public String mode() { return "DISABLED"; }
            };
        }
    }
}
