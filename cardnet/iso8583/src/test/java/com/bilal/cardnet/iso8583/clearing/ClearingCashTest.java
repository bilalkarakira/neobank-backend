package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile.RecordKind;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cash withdrawals in a clearing file: processing code 010000, cleared like a purchase. */
class ClearingCashTest {

    static final String NAME = "ACQ-20260101-0001.clr";
    final ClearingFileCodec codec = ClearingFileCodec.withSecret("test-secret");

    static Message cash(int n, String amount) {
        return ClearingFileCodecTest.purchase(n, amount).set(3, "010000").set(18, "6011");
    }

    static ClearingFile file(Message... items) {
        return new ClearingFile("B0001", LocalDate.of(2026, 1, 1), "ACQ", "NET", "USD", List.of(items));
    }

    @Test
    void aCashWithdrawalRoundTripsNextToPurchasesAndRefunds() {
        ClearingFile in = file(ClearingFileCodecTest.purchase(1, "000000005000"), cash(2, "000000010000"), ClearingFileCodecTest.refund(3, "000000000099"));

        ClearingFile out = codec.decode(codec.encode(in, NAME), NAME);

        assertThat(out).isEqualTo(in);
        assertThat(out.presentments().get(1).get(3)).isEqualTo("010000");
        assertThat(out.controlTotal()).isEqualTo(15099);
    }

    @Test
    void cashIsAPurchaseLikeRecordAndHasItsOwnItemKey() {
        Message purchase = ClearingFileCodecTest.purchase(1, "000000001000");
        Message cash = purchase.copyWithMti("1240").set(3, "010000");

        assertThat(ClearingFile.kindOf(cash)).isEqualTo(RecordKind.PURCHASE);
        assertThat(ClearingFile.key(cash)).isNotEqualTo(ClearingFile.key(purchase));
        assertThat(codec.decode(codec.encode(file(purchase, cash), NAME), NAME).presentments()).as("same identifiers, different code: two items").hasSize(2);
    }

    @Test
    void aCashWithdrawalNeedsItsAuthorizationCodeLikeAPurchase() {
        assertThatThrownBy(() -> codec.encode(file(cash(1, "000000002000").remove(38)), NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_ELEMENT))
                .hasMessageContaining("cash withdrawal presentment without DE 38");
        assertThatThrownBy(() -> codec.encode(file(ClearingFileCodecTest.purchase(1, "000000002000").remove(38)), NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_ELEMENT))
                .hasMessageContaining("purchase presentment without DE 38");
        assertThat(codec.encode(file(ClearingFileCodecTest.refund(1, "000000002000")), NAME)).as("a refund has no authorization code").isNotEmpty();
    }

    @Test
    void onlyThePurchaseTheCashAndTheRefundCodeAreAccepted() {
        for (String code : List.of("300000", "010001", "000001", "200001", "990000", "400000", "100000", "000010")) {
            assertThatThrownBy(() -> codec.encode(file(cash(1, "000000002000").set(3, code)), NAME))
                    .as(code).isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_PRESENTMENT))
                    .hasMessageContaining("neither purchase, cash withdrawal nor refund");
        }
        for (String code : List.of("000000", "010000", "200000")) {
            Message m = cash(1, "000000002000").set(3, code);
            assertThat(codec.decode(codec.encode(file(code.equals("200000") ? m.remove(38) : m), NAME), NAME).presentments()).as(code).hasSize(1);
        }
    }

    @Test
    void aDisputeRecordStillCarriesTheOrdinaryCode() {
        Message chargeback = Message.of("1442").set(2, "9999000000000012").set(3, "010000").set(4, "000000002000").set(7, "1006120000").set(11, "000001")
                .set(12, "120000").set(13, "1006").set(32, "00000123456").set(37, "000000000001").set(41, "TERM0001").set(42, "MERCHANT000001 ")
                .set(49, "840").set(48, "CB|OTHER|x");

        assertThatThrownBy(() -> codec.encode(new ClearingFile("D0000001", LocalDate.of(2026, 1, 1), "999900", "NET", "USD", List.of(chargeback)), "D0000001.dsp"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_PRESENTMENT));
    }
}
