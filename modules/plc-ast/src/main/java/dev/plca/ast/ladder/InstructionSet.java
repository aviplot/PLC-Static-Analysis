package dev.plca.ast.ladder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Rockwell Logix 5000 instructions the analyzer knows: whether an instruction is an input (it conditions the rung)
 * or an output, and what it does with each operand. Ladder lowering uses the kind; the semantic model derives reads and
 * writes from the roles. Loaded once from {@code instructions.txt}, which documents the format and sources.
 */
public final class InstructionSet {

    public enum Kind {
        /** Conditions the rung: its result is ANDed onto the rung condition. */
        INPUT,
        /** Runs when the rung condition is true. */
        OUTPUT,
        /** Runs every scan with the rung condition as its input (timers, counters, one-shots, alarms). */
        ALWAYS
    }

    public enum Role {
        READ,
        WRITE,
        READ_WRITE,
        /** A structure the instruction keeps its state in (TIMER, COUNTER, CONTROL, MESSAGE, …): read and written. */
        INSTANCE,
        /** A word that is not a tag: GSV class and attribute names, FAL modes. */
        NAME,
        ROUTINE,
        LABEL,
        EXPRESSION,
        /** A value shown for information only (timer preset/accum in rung text); not lowered. */
        DISPLAY,
        /** The number of input parameters that follow ({@code JSR}). */
        COUNT,
        /**
         * {@code JSR}/{@code SBR}/{@code RET}-style parameter: the first {@code COUNT} are inputs, the rest returns.
         */
        PARAMETER
    }

    /** One instruction; {@code repeatLast} when the last role applies to every further operand. */
    public record Instruction(String mnemonic, Kind kind, List<Role> roles, boolean repeatLast, boolean verified) {
        public Instruction {
            roles = List.copyOf(roles);
        }

        /** The role of operand {@code index}, or null past the end of a fixed operand list. */
        public Role role(int index) {
            if (index < roles.size()) {
                return roles.get(index);
            }
            return repeatLast && !roles.isEmpty() ? roles.getLast() : null;
        }
    }

    private static final Map<String, Instruction> INSTRUCTIONS = load();

    private InstructionSet() {}

    /** The instruction named {@code mnemonic} (case-insensitive). */
    public static Optional<Instruction> lookup(String mnemonic) {
        return Optional.ofNullable(INSTRUCTIONS.get(mnemonic.toUpperCase(Locale.ROOT)));
    }

    public static Map<String, Instruction> all() {
        return Collections.unmodifiableMap(INSTRUCTIONS);
    }

    private static Map<String, Instruction> load() {
        Map<String, Instruction> map = new HashMap<>();
        try (InputStream in = InstructionSet.class.getResourceAsStream("instructions.txt")) {
            if (in == null) {
                throw new IllegalStateException("instructions.txt missing from the classpath");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                Instruction i = parse(s, lineNo);
                if (map.put(i.mnemonic(), i) != null) {
                    throw new IllegalStateException("instructions.txt:" + lineNo + ": duplicate " + i.mnemonic());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    private static Instruction parse(String s, int lineNo) {
        String[] parts = s.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalStateException("instructions.txt:" + lineNo + ": mnemonic and kind expected");
        }
        Kind kind = switch (parts[1]) {
            case "in" -> Kind.INPUT;
            case "out" -> Kind.OUTPUT;
            case "always" -> Kind.ALWAYS;
            default -> throw new IllegalStateException("instructions.txt:" + lineNo + ": unknown kind " + parts[1]);
        };
        List<Role> roles = new ArrayList<>();
        boolean repeat = false;
        boolean verified = true;
        for (int k = 2; k < parts.length; k++) {
            String p = parts[k];
            if (p.equals("?")) {
                verified = false;
                continue;
            }
            if (repeat) {
                throw new IllegalStateException("instructions.txt:" + lineNo + ": '*' must be on the last role");
            }
            if (p.endsWith("*")) {
                repeat = true;
                p = p.substring(0, p.length() - 1);
            }
            roles.add(
                    switch (p) {
                        case "r" -> Role.READ;
                        case "w" -> Role.WRITE;
                        case "rw" -> Role.READ_WRITE;
                        case "inst" -> Role.INSTANCE;
                        case "name" -> Role.NAME;
                        case "routine" -> Role.ROUTINE;
                        case "label" -> Role.LABEL;
                        case "expr" -> Role.EXPRESSION;
                        case "disp" -> Role.DISPLAY;
                        case "n" -> Role.COUNT;
                        case "jsr" -> Role.PARAMETER;
                        default ->
                            throw new IllegalStateException("instructions.txt:" + lineNo + ": unknown role " + p);
                    });
        }
        return new Instruction(parts[0].toUpperCase(Locale.ROOT), kind, roles, repeat, verified);
    }
}
