package com.marketplace.inventory.domain;

/**
 * Estados de una reserva. HELD es el único no terminal: de él se sale una vez y para siempre.
 *
 * <p>Se persiste como texto ({@code EnumType.STRING}), por lo aprendido en el módulo 3: con
 * ORDINAL, reordenar estas constantes cambiaría el significado de todas las filas ya escritas.
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    RELEASED
}
