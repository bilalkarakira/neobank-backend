package com.bilal.cardnet.iso8583;

import com.bilal.cardnet.iso8583.FieldDef.Prefix;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The set of data elements a packager understands. {@link #ISO87} is the profile documented in docs/ISO8583-spec.md. */
public final class Spec {

    public static final Spec ISO87 = new Spec(
            new FieldDef(2, "Primary account number", FieldType.N, Prefix.LL, 19, true),
            new FieldDef(3, "Processing code", FieldType.N, Prefix.FIXED, 6),
            new FieldDef(4, "Amount, transaction", FieldType.N, Prefix.FIXED, 12),
            new FieldDef(7, "Transmission date and time", FieldType.N, Prefix.FIXED, 10),
            new FieldDef(11, "System trace audit number", FieldType.N, Prefix.FIXED, 6),
            new FieldDef(12, "Local transaction time", FieldType.N, Prefix.FIXED, 6),
            new FieldDef(13, "Local transaction date", FieldType.N, Prefix.FIXED, 4),
            new FieldDef(14, "Expiration date", FieldType.N, Prefix.FIXED, 4),
            new FieldDef(18, "Merchant category code", FieldType.N, Prefix.FIXED, 4),
            new FieldDef(22, "POS entry mode", FieldType.N, Prefix.FIXED, 3),
            new FieldDef(25, "POS condition code", FieldType.N, Prefix.FIXED, 2),
            new FieldDef(32, "Acquiring institution id", FieldType.N, Prefix.LL, 11),
            new FieldDef(37, "Retrieval reference number", FieldType.AN, Prefix.FIXED, 12),
            new FieldDef(38, "Authorization id response", FieldType.AN, Prefix.FIXED, 6),
            new FieldDef(39, "Response code", FieldType.AN, Prefix.FIXED, 2),
            new FieldDef(41, "Card acceptor terminal id", FieldType.ANS, Prefix.FIXED, 8),
            new FieldDef(42, "Card acceptor id", FieldType.ANS, Prefix.FIXED, 15),
            new FieldDef(43, "Card acceptor name/location", FieldType.ANS, Prefix.FIXED, 40),
            new FieldDef(48, "Additional data, private", FieldType.ANS, Prefix.LLL, 999),
            new FieldDef(49, "Currency code, transaction", FieldType.N, Prefix.FIXED, 3),
            new FieldDef(52, "PIN data", FieldType.B, Prefix.FIXED, 16, true),
            new FieldDef(55, "ICC data", FieldType.B, Prefix.LLL, 510),
            new FieldDef(70, "Network management information code", FieldType.N, Prefix.FIXED, 3),
            new FieldDef(90, "Original data elements", FieldType.N, Prefix.FIXED, 42));

    private final Map<Integer, FieldDef> fields = new TreeMap<>();

    public Spec(FieldDef... definitions) {
        for (FieldDef def : definitions) {
            if (def.number() < 2 || def.number() > 128 || def.number() == 65) {
                throw new IllegalArgumentException("field " + def.number() + " cannot be defined");
            }
            if (fields.put(def.number(), def) != null) {
                throw new IllegalArgumentException("field " + def.number() + " defined twice");
            }
        }
    }

    public Optional<FieldDef> get(int number) {
        return Optional.ofNullable(fields.get(number));
    }

    public Collection<FieldDef> all() {
        return fields.values();
    }
}
