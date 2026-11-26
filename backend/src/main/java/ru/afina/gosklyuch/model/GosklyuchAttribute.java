package ru.afina.gosklyuch.model;

/**
 * Дополнительный атрибут документа в заявке Госключа
 * (элемент {@code Attribute}, не более 10 на документ).
 */
public record GosklyuchAttribute(String name, String value) {
}
