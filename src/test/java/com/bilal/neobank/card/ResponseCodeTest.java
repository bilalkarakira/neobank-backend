package com.bilal.neobank.card;

import com.bilal.neobank.card.auth.ResponseCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCodeTest {

    private static final String FALLBACK = ResponseCode.reason("zz");

    @Test
    void anApprovalHasNoReason() {
        assertThat(ResponseCode.reason(ResponseCode.APPROVED)).isNull();
    }

    @Test
    void everyCodeTheIssuerCanAnswerHasItsOwnPlainReason() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Field field : ResponseCode.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && Modifier.isPublic(field.getModifiers())) {
                String code = (String) field.get(null);
                if (!code.equals(ResponseCode.APPROVED) && ResponseCode.reason(code).equals(FALLBACK)
                        && !code.equals(ResponseCode.SYSTEM_ERROR)) {
                    missing.add(field.getName());
                }
            }
        }
        assertThat(missing).as("codes that only get the generic fallback text").isEmpty();
    }

    @Test
    void aRefusedAdditionToAPaymentIsExplainedInItsOwnWords() {
        assertThat(ResponseCode.reason(ResponseCode.INVALID_TRANSACTION)).contains("added to").isNotEqualTo(FALLBACK);
    }

    @Test
    void aFrozenCardIsNotDescribedAsABlockedKindOfPayment() {
        assertThat(ResponseCode.reason(ResponseCode.NOT_PERMITTED)).contains("frozen").contains("not allowed");
    }

    @Test
    void unknownCodesGetAGenericSentenceNotAnException() {
        assertThat(ResponseCode.reason("zz")).isEqualTo("The payment could not be processed.");
    }
}
