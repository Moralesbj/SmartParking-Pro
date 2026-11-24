package com.smartparking.modules.vehicles.domain;

import java.util.Arrays;

/**
 * The only four vehicle categories. Sub-kinds (automóvil, camioneta, campero…) are not separate categories:
 * they share the category's tariff in each branch.
 */
public enum VehicleCategory {
    MOTORCYCLE("Motocicleta", "Motocicletas y motonetas"),
    CAR("Carro", "Automóviles, camionetas y camperos"),
    BICYCLE("Bicicleta", "Bicicletas convencionales y eléctricas"),
    CARGO("Vehículo de carga", "Camiones y vehículos de transporte de mercancías");

    public static final String PATTERN = "MOTORCYCLE|CAR|BICYCLE|CARGO";
    private final String label, includes;

    VehicleCategory(String label, String includes) { this.label = label; this.includes = includes; }
    public String label() { return label; }
    public String includes() { return includes; }

    public static VehicleCategory parse(String value) {
        return Arrays.stream(values()).filter(c -> c.name().equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Categoría de vehículo inválida"));
    }

    public static String[] names() { return Arrays.stream(values()).map(Enum::name).toArray(String[]::new); }
}
