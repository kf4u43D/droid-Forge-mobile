package fr.androforge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Regressions for physical port identities and reference faceplate coordinates. */
public final class ModuleGeometryTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < .0001f, message + ": " + actual + " != " + expected);
    }

    private static ModuleGeometry.Port find(List<ModuleGeometry.Port> ports, String register) {
        for (ModuleGeometry.Port port : ports) if (register.equals(port.register)) return port;
        throw new AssertionError("Missing physical port " + register);
    }

    private static void coordinates(ModuleGeometry.Port port, float x, float y) {
        near(port.x, x, port.register + " x"); near(port.y, y, port.register + " y");
    }

    private static void physicalBounds(String name, List<ModuleGeometry.Port> ports) {
        Set<String> names = new HashSet<>();
        for (ModuleGeometry.Port port : ports) {
            check(names.add(port.register), name + " duplicate " + port.register);
            check(port.x > 0 && port.x < ModuleGeometry.width(name)
                && port.y > 0 && port.y < ModuleGeometry.HEIGHT, name + " faceplate port outside bounds");
            check(Float.isFinite(port.labelX) && Float.isFinite(port.labelY) && port.width > 0
                && port.height > 0 && port.labelWidth > 0, name + " invalid control/label geometry");
        }
        boolean immutable = false;
        try { ports.add(null); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Shared geometry cache must not expose mutable lists");
    }

    public static void main(String[] args) {
        List<ModuleGeometry.Port> master = ModuleGeometry.ports("master", 0, 0, 0);
        check(master.size() == 16, "MASTER16 must expose eight inputs and eight outputs");
        coordinates(find(master, "I1"), .98f, 14.47f);
        coordinates(find(master, "I8"), 6.98f, 17.02f);
        coordinates(find(master, "O8"), 6.98f, 22.12f);
        List<ModuleGeometry.Port> master18 = ModuleGeometry.ports("master18", 0, 0, 0);
        check(master18.size() == 14, "MASTER18 must expose two inputs, eight outputs and four gates");
        coordinates(find(master18, "I2"), .98f, 11.92f);
        coordinates(find(master18, "G1.4"), .98f, 22.12f);
        check(master18.stream().noneMatch(p -> p.register.equals("G4") || p.register.equals("I3")), "MASTER18 gates require the G1 prefix");
        List<ModuleGeometry.Port> firstG8 = ModuleGeometry.ports("g8", 0, 1, 16);
        List<ModuleGeometry.Port> secondG8 = ModuleGeometry.ports("g8", 0, 2, 24);
        coordinates(find(firstG8, "G1.8"), 2.98f, 22.12f);
        coordinates(find(secondG8, "G2.1"), .98f, 14.47f);
        check(firstG8.size() == 8 && secondG8.size() == 8, "Expanders use separate gate banks");
        List<ModuleGeometry.Port> x7 = ModuleGeometry.ports("x7", 0, 0, 48);
        check(x7.size() == 4, "X7 must expose four global gates");
        coordinates(find(x7, "G9"), .98f, 19.57f);
        coordinates(find(x7, "G12"), 2.98f, 22.12f);
        check(x7.stream().noneMatch(p -> p.register.startsWith("G1.")), "X7 gates must not alias the first G8");
        near(ModuleGeometry.width("m4"), 14, "M4 width in HP");
        near(ModuleGeometry.width("b32"), 10, "B32 width in HP");
        List<ModuleGeometry.Port> m4 = ModuleGeometry.ports("m4", 3, 0, 0);
        coordinates(find(m4, "P3.4"), 12.244f, 18.37f);
        coordinates(find(m4, "B3.4"), 12.324f, 22.94f);
        near(find(m4, "P3.1").height, 4.4f, "M4 fader aspect");
        List<ModuleGeometry.Port> b32 = ModuleGeometry.ports("b32", 2, 0, 0);
        check(b32.size() == 32, "B32 must expose all thirty-two buttons");
        coordinates(find(b32, "B2.32"), 8.758f, 22.359f);
        List<ModuleGeometry.Port> db8e = ModuleGeometry.ports("db8e", 4, 0, 0);
        coordinates(find(db8e, "E4.1"), 3, 20.8f);
        coordinates(find(db8e, "B4.9"), 3, 20.8f);
        check(db8e.size() == 10, "DB8E encoder button has index nine");
        List<ModuleGeometry.Port> masterRendering = ModuleGeometry.renderingPorts("master", 0, 0, 0);
        check(masterRendering.size() == 40, "Imported normalization and RGB labels must be renderable");
        coordinates(find(masterRendering, "R16"), 6.675f, 11.575f);
        coordinates(find(masterRendering, "N1"), .98f, 14.47f);
        List<ModuleGeometry.Port> g8Rendering = ModuleGeometry.renderingPorts("g8", 0, 2, 24);
        coordinates(find(g8Rendering, "R25"), 1.15f, 5.88f);
        coordinates(find(g8Rendering, "R32"), 2.925f, 11.575f);
        List<ModuleGeometry.Port> x7Rendering = ModuleGeometry.renderingPorts("x7", 0, 0, 48);
        check(x7Rendering.size() == 12, "X7 imported labels include R49 through R56");
        coordinates(find(x7Rendering, "R49"), 1.15f, 5.88f);
        find(x7Rendering, "R56");
        check(x7.stream().noneMatch(p -> p.register.startsWith("R")), "Read-only RGB rendering must not change the original interactive picker");
        List<ModuleGeometry.Port> m4Rendering = ModuleGeometry.renderingPorts("m4", 3, 0, 0);
        coordinates(find(m4Rendering, "L3.4"), 12.324f, 22.94f);
        for (String name : new String[]{"master", "master18", "g8", "x7", "p2b8", "p4b2", "p10", "s10", "p8s8", "e4", "db8e", "m4", "b32"}) {
            physicalBounds(name, ModuleGeometry.ports(name, 2, name.equals("g8") ? 2 : 0, name.equals("x7") ? 48 : 24));
            physicalBounds(name, ModuleGeometry.renderingPorts(name, 2, name.equals("g8") ? 2 : 0, name.equals("x7") ? 48 : 24));
        }
        System.out.println("Module geometry: " + checks + " checks passed");
    }
}
