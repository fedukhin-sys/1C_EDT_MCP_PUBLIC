package ru.fedukhin.edt.mcp.core.privacy;

/** Класс чувствительности значения. Порядок важен: чем выше ordinal, тем строже (для merge). */
public enum Sensitivity {
    NONE, ORGANIZATION, COUNTERPARTY, PERSONAL, BIOMETRIC, SPECIAL;

    public boolean isSensitive() { return this != NONE; }

    /** Спец-категории и биометрия скрываются полностью, без стабильного токена. */
    public boolean fullHide() { return this == SPECIAL || this == BIOMETRIC; }

    /** Метка токена: «Контрагент», «Физлицо» и т.п. */
    public String label() {
        return switch (this) {
            case PERSONAL -> "Физлицо";
            case COUNTERPARTY -> "Контрагент";
            case ORGANIZATION -> "Организация";
            case BIOMETRIC -> "Биометрия";
            case SPECIAL -> "СпецКатегория";
            case NONE -> "";
        };
    }
}
