package fr.androforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Faceplate coordinates in HP, from DROID Forge 1.7.1 Module*.cpp (GPL-3.0). */
final class ModuleGeometry {
    static final float HEIGHT = 12.8f / .508f;

    static final class Port {
        final String register;
        final float x, y, width, height, labelX, labelY, labelWidth;
        Port(String register, float x, float y, float size, float aspect,
             float distance, float labelWidth, float customX, float customY) {
            this.register = register; this.x = x; this.y = y;
            width = size * 2f / 3f; height = width * (aspect == 0 ? 1 : aspect);
            this.labelWidth = labelWidth;
            labelX = customY == 0 ? x - labelWidth / 2 : customX;
            labelY = customY == 0 ? y + height / 2 + distance : customY;
        }
    }

    private ModuleGeometry() {}

    static float width(String name) {
        switch (name) {
            case "master": case "p8s8": return 8;
            case "master18": case "e4": case "db8e": return 6;
            case "b32": return 10;
            case "m4": return 14;
            case "g8": case "x7": return 4;
            case "p10": case "p2b8": case "p4b2": case "s10": return 5;
            default: return 5;
        }
    }

    static List<Port> ports(String name, int controller, int g8, int rgbOffset) {
        List<Port> ports = new ArrayList<>();
        switch (name) {
            case "master":
                for (char type : new char[]{'I', 'O'}) for (int n = 1; n <= 8; n++)
                    add(ports, type, 0, 0, n, ((n - 1) % 4) * 2f + .98f,
                            ((n - 1) / 4) * 2.55f + (type == 'I' ? 14.47f : 19.57f),
                            2.5f, 0, -2.45f, 1.93f);
                break;
            case "master18":
                for (int n = 1; n <= 2; n++) add(ports, 'I', 0, 0, n, .98f, (n - 1) * 2.55f + 9.37f, 2.5f, 0, -2.45f, 1.93f);
                for (int n = 1; n <= 8; n++) add(ports, 'O', 0, 0, n, (1 + (n - 1) % 2) * 2f + .98f,
                        (2 + (n - 1) / 2) * 2.55f + 9.37f, 2.5f, 0, -2.45f, 1.93f);
                for (int n = 1; n <= 4; n++) add(ports, 'G', 0, 1, n, .98f, (n + 1) * 2.55f + 9.37f, 2.5f, 0, -2.45f, 1.93f);
                break;
            case "b32":
                for (int n = 1; n <= 32; n++) add(ports, 'B', controller, 0, n,
                        ((n - 1) % 4) * 2.5f + 1.258f, ((n - 1) / 4) * 2.767f + 2.99f, 2.7f, 0, -2.62f, 2.43f);
                break;
            case "db8e":
                add(ports, 'E', controller, 0, 1, 3, 20.8f, 4.1f, 0, -4.6f, 6);
                for (int n = 1; n <= 9; n++) add(ports, 'B', controller, 0, n,
                        n == 9 ? 3 : ((n - 1) % 2) * 3f + 1.5f,
                        n == 9 ? 20.8f : ((n - 1) / 2) * 2.97f + 7.95f,
                        n == 9 ? 4.1f : 2.7f, 0, -2.65f, 2.6f);
                break;
            case "e4":
                for (char type : new char[]{'E', 'B'}) for (int n = 1; n <= 4; n++)
                    add(ports, type, controller, 0, n, 3, (n - 1) * 5.49f + 4.33f, 4.1f, 0, type == 'E' ? -4.4f : -3.68f, 5);
                break;
            case "g8": case "x7":
                for (int n = 1; n <= (name.equals("g8") ? 8 : 4); n++)
                    add(ports, 'G', 0, name.equals("g8") ? g8 : 0, name.equals("g8") ? n : n + 8,
                            ((n - 1) % 2) * 2f + .98f,
                            ((n - 1) / 2 + (name.equals("x7") ? 2 : 0)) * 2.55f + 14.47f, 2.5f, 0, -2.45f, 1.93f);
                break;
            case "m4":
                for (char type : new char[]{'P', 'B'}) for (int n = 1; n <= 4; n++)
                    add(ports, type, controller, 0, n, (n - 1) * 3.498f + 1.83f - (type == 'P' ? .08f : 0),
                            type == 'P' ? 18.37f : 22.94f, type == 'P' ? 3 : 2.65f,
                            type == 'P' ? 2.2f : .7f, type == 'P' ? -15.34f : -2.35f, 3.4f);
                break;
            case "p2b8": case "p4b2":
                for (int n = 1; n <= (name.equals("p2b8") ? 2 : 4); n++)
                    add(ports, 'P', controller, 0, n, 2.5f, (n - 1) * 4.91f + 3.5f, 4.1f, 0, -4, 4.8f);
                for (int n = 1; n <= (name.equals("p2b8") ? 8 : 2); n++)
                    add(ports, 'B', controller, 0, n, ((n - 1) % 2) * 2.5f + 1.27f,
                            (name.equals("p2b8") ? (n - 1) / 2 : 3) * 2.97f + 13.43f, 2.7f, 0, -2.62f, 2.43f);
                break;
            case "p10": case "s10":
                for (int n = 1; n <= 10; n++) {
                    boolean pot = name.equals("p10");
                    add(ports, pot ? 'P' : 'S', controller, 0, n,
                            n <= 2 ? 2.5f : ((n - 3) % 2) * (pot ? 2.42f : 2.36f) + (pot ? 1.3f : 1.32f),
                            n <= 2 ? (n - 1) * 4.91f + 3.5f : ((n - 3) / 2) * (pot ? 2.94f : 2.964f) + (pot ? 12.93f : 13.35f),
                            n <= 2 ? 4.1f : pot ? 1.5f : 2,
                            0, n <= 2 ? -4 : pot ? -2.05f : -2.31f, n <= 2 ? 4.8f : pot ? 2.35f : 2.29f);
                }
                break;
            case "p8s8":
                for (char type : new char[]{'P', 'S'}) for (int n = 1; n <= 8; n++) {
                    float x = ((n - 1) % 4) * 1.985f + 1.05f;
                    float y = ((n - 1) / 4) * (type == 'P' ? 7.87f : 2.82f) + (type == 'P' ? 8.08f : 19.45f);
                    String register = register(type, controller, 0, n);
                    ports.add(new Port(register, x, y, type == 'P' ? 1.99f : 2.7f, type == 'P' ? 1.2f : 0,
                            type == 'P' ? -4 : -2.62f, type == 'P' ? 3 : 2,
                            type == 'P' ? ((n - 1) % 4) * 1.65f + .05f : 0,
                            type == 'P' ? 3.9f + ((n - 1) % 4) * .75f + (n >= 5 ? 7.8f : 0) : 0));
                }
                break;
            default: break;
        }
        return Collections.unmodifiableList(ports);
    }

    /** Forge also paints imported LED/normalization/RGB labels omitted by its editor. */
    static List<Port> renderingPorts(String name, int controller, int g8, int rgbOffset) {
        List<Port> ports = new ArrayList<>(ports(name, controller, g8, rgbOffset));
        List<Port> additional = new ArrayList<>();
        for (Port port : ports) {
            char type = port.register.charAt(0);
            if (name.equals("master") && type == 'I')
                additional.add(new Port('N' + port.register.substring(1), port.x, port.y, 2.5f, 0, -2.45f, 1.93f, 0, 0));
            else if (controller > 0 && type == 'B') {
                boolean encoder = name.equals("e4") || name.equals("db8e") && port.register.endsWith(".9");
                float size = encoder ? 7 : name.equals("m4") ? 2.65f : 2.7f;
                float aspect = encoder ? 1 : name.equals("m4") ? .7f : 0;
                float distance = name.equals("e4") ? -3.68f : name.equals("db8e") ? -2.65f : name.equals("m4") ? -2.35f : -2.62f;
                additional.add(new Port('L' + port.register.substring(1), port.x, port.y, size, aspect, distance, port.labelWidth, 0, 0));
            } else if (name.equals("p8s8") && type == 'P')
                additional.add(new Port('L' + port.register.substring(1), port.x, port.y, 1.99f, 0, -2.62f, 2, 0, 0));
        }
        if (name.equals("master") || name.equals("g8") || name.equals("x7")) {
            int columns = name.equals("master") ? 4 : 2, count = name.equals("master") ? 16 : 8;
            for (int n = 1; n <= count; n++) {
                int row = (n - 1) / columns, column = (n - 1) % columns;
                float x = column * 1.775f + (name.equals("master") ? 1.35f : 1.15f);
                float y = row * 1.775f + 5.88f + (row >= 2 ? .37f : 0);
                add(additional, 'R', 0, 0, n + (name.equals("master") ? 0 : rgbOffset), x, y, 2.1f, 1, -2.45f, 1.93f);
            }
        }
        ports.addAll(additional);
        // The PC uses a sorted register map; overlapping imported labels have that order.
        ports.sort((a, b) -> a.register.compareTo(b.register));
        return Collections.unmodifiableList(ports);
    }

    private static void add(List<Port> ports, char type, int controller, int g8, int number,
                            float x, float y, float size, float aspect, float distance, float width) {
        ports.add(new Port(register(type, controller, g8, number), x, y, size, aspect, distance, width, 0, 0));
    }

    private static String register(char type, int controller, int g8, int number) {
        return "" + type + (controller > 0 ? controller + "." : g8 > 0 ? g8 + "." : "") + number;
    }
}
