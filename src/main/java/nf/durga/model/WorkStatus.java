package nf.durga.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WorkStatus {
    TODO("TODO"),
    ON_IT("ON IT"),
    PAUSE("PAUSE"),
    FINISHED("FINISHED");

    private final String value;

    WorkStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static WorkStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (WorkStatus workStatus : values()) {
            if (workStatus.value.equalsIgnoreCase(value)
                    || workStatus.name().equalsIgnoreCase(value)
                    || workStatus.name().equalsIgnoreCase(value.replace(' ', '_'))) {
                return workStatus;
            }
        }
        throw new IllegalArgumentException("État de travail invalide : " + value);
    }
}
