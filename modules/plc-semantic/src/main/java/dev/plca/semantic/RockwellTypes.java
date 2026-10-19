package dev.plca.semantic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Predefined Rockwell Logix 5000 structures that logic addresses member by member ({@code T1.DN}, {@code C1.ACC},
 * {@code Ctl.POS}, {@code Name.LEN}), looked up only for declarations of dialect ROCKWELL. Members: Logix 5000 General
 * Instructions Reference Manual (1756-RM003) and the STRING data type (82 characters). Other predefined types
 * ({@code MESSAGE}, {@code PID}, {@code ALARM_DIGITAL}, motion types) stay unknown: their members count as library
 * names, never as unresolved.
 */
final class RockwellTypes {

    private final Map<String, TypeSymbol> types = new HashMap<>();

    RockwellTypes() {
        struct("TIMER", List.of(dint("PRE"), dint("ACC"), bool("EN"), bool("TT"), bool("DN")));
        struct(
                "COUNTER",
                List.of(dint("PRE"), dint("ACC"), bool("CU"), bool("CD"), bool("DN"), bool("OV"), bool("UN")));
        struct(
                "CONTROL",
                List.of(
                        dint("LEN"),
                        dint("POS"),
                        bool("EN"),
                        bool("EU"),
                        bool("DN"),
                        bool("EM"),
                        bool("ER"),
                        bool("UL"),
                        bool("IN"),
                        bool("FD")));
        struct(
                "STRING",
                List.of(
                        dint("LEN"),
                        new Member(
                                "DATA",
                                new Type.Array(List.of(new Type.Dimension(0L, 81L)), Type.of(ElementaryKind.SINT)))));
    }

    private record Member(String name, Type type) {}

    private static Member dint(String name) {
        return new Member(name, Type.of(ElementaryKind.DINT));
    }

    private static Member bool(String name) {
        return new Member(name, Type.BOOL);
    }

    private void struct(String name, List<Member> members) {
        TypeSymbol s = new TypeSymbol(name, TypeSymbol.Kind.STRUCT, null, null, List.of());
        for (Member m : members) {
            VariableSymbol v = new VariableSymbol(m.name(), null, null, dev.plca.ast.VarBlock.Section.VAR, s, false);
            v.setType(m.type());
            s.addMember(v);
        }
        s.setType(new Type.Struct(s));
        types.put(PouSymbol.key(name), s);
    }

    Optional<TypeSymbol> lookup(String name) {
        return Optional.ofNullable(types.get(PouSymbol.key(name)));
    }
}
