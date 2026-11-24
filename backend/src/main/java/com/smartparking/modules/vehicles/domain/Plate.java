package com.smartparking.modules.vehicles.domain;

import java.util.Locale;

public final class Plate {
    private Plate() {}
    /** Motor vehicles carry a Colombian plate; bicycles have no plate and use their registry or serial (3–12 letters/digits). */
    public static String normalize(String value, String country, String type) {
        if (!"BICYCLE".equals(type)) return normalize(value, country);
        if (value == null || !"CO".equals(country)) throw new IllegalArgumentException("Solo Colombia está habilitada");
        String normalized = value.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (!normalized.matches("[A-Z0-9]{3,12}")) throw new IllegalArgumentException("Identificación de bicicleta inválida");
        return normalized;
    }

    public static String normalize(String value, String country) {
        if(value==null || !"CO".equals(country)) throw new IllegalArgumentException("Solo Colombia está habilitada");
        String normalized=value.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if(!normalized.matches("[A-Z]{3}([0-9]{3}|[0-9]{2}[A-Z])")) throw new IllegalArgumentException("Placa colombiana inválida");
        return normalized;
    }
}
