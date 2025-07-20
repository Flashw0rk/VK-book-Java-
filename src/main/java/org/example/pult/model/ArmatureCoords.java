package org.example.pult.model;

/**
 * Класс для хранения координатной информации об арматуре на PDF-схеме.
 * Используется для десериализации из JSON-файла armature_coords.json.
 */
public class ArmatureCoords {
    public int page;        // Номер страницы в PDF (начиная с 1)
    public double x;        // X-координата левого верхнего угла области арматуры в PDF-единицах
    public double y;        // Y-координата левого верхнего угла области арматуры в PDF-единицах
    public double width;    // Ширина области арматуры в PDF-единицах
    public double height;   // Высота области арматуры в PDF-единицах
    public double zoom;     // Желаемый коэффициент масштабирования при переходе к этой арматуре
    public String label;    // Текст для отображения в качестве наложения/метки
    public String marker_type; // Тип маркера (для разных иконок/стилей, опционально)

    // Пустой конструктор необходим для Jackson (или других JSON-библиотек)
    public ArmatureCoords() {}

    // Конструктор со всеми полями (опционально, но полезно для инициализации)
    public ArmatureCoords(int page, double x, double y, double width, double height, double zoom, String label, String marker_type) {
        this.page = page;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.zoom = zoom;
        this.label = label;
        this.marker_type = marker_type;
    }

    // Также можно добавить геттеры и сеттеры, если вы предпочитаете их использовать вместо прямых публичных полей.
    // Для Jackson достаточно публичных полей или публичных геттеров/сеттеров.

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }

    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }

    public double getZoom() { return zoom; }
    public void setZoom(double zoom) { this.zoom = zoom; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getMarker_type() { return marker_type; }
    public void setMarker_type(String marker_type) { this.marker_type = marker_type; }

    @Override
    public String toString() {
        return "ArmatureCoords{" +
                "page=" + page +
                ", x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                ", zoom=" + zoom +
                ", label='" + label + '\'' +
                ", marker_type='" + marker_type + '\'' +
                '}';
    }
}