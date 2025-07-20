package org.example.pult;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.*;

public class RowDataDynamic {
    private final Map<String, StringProperty> properties = new LinkedHashMap<>();

    public RowDataDynamic() {
    }

    public RowDataDynamic(Map<String, String> initialData) {
        if (initialData != null) {
            initialData.forEach(this::put);
        }
    }

    public StringProperty getProperty(String header) {
        return properties.computeIfAbsent(header, k -> new SimpleStringProperty(this, k));
    }

    public void put(String header, String value) {
        getProperty(header).set(value);
    }

    public Collection<StringProperty> getAllProperties() {
        return properties.values();
    }

    public Map<String, StringProperty> getAll() {
        return new LinkedHashMap<>(properties);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RowDataDynamic{");
        properties.forEach((key, value) -> sb.append(key).append("='").append(value.get()).append("', "));
        if (!properties.isEmpty()) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}");
        return sb.toString();
    }
}