/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2016 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.filter;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.zimbra.common.util.ArrayUtil;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.MockProvisioning;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.filter.RuleManager;
import com.zimbra.cs.lmtpserver.LmtpAddress;
import com.zimbra.cs.lmtpserver.LmtpEnvelope;
import com.zimbra.cs.mailbox.DeliveryContext;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.MailboxTestUtil;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mailbox.OperationContext;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.cs.service.util.ItemId;

public class RelationalExtensionTest {
    private static String sampleMsg = "Received: from edge01e.zimbra.com ([127.0.0.1])\n"
            + "\tby localhost (edge01e.zimbra.com [127.0.0.1]) (amavisd-new, port 10032)\n"
            + "\twith ESMTP id DN6rfD1RkHD7; Fri, 24 Jun 2016 01:45:31 -0400 (EDT)\n"
            + "Received: from localhost (localhost [127.0.0.1])\n"
            + "\tby edge01e.zimbra.com (Postfix) with ESMTP id 9245B13575C;\n"
            + "\tFri, 24 Jun 2016 01:45:31 -0400 (EDT)\n"
            + "x-priority: 1\n"
            + "from: xyz@example.com\n"
            + "Subject: =?ISO-2022-JP?B?GyRCJDMkcyRLJEEkTxsoQg==?=\n"
            + "to: foo@example.com, baz@example.com\n"
            + "cc: qux@example.com\n";

    @BeforeClass
    public static void init() throws Exception {
        MailboxTestUtil.initServer();
        Provisioning prov = Provisioning.getInstance();
        prov.createAccount("test@zimbra.com", "secret",
                new HashMap<String, Object>());
    }

    @Before
    public void setUp() throws Exception {
        MailboxTestUtil.clearData();
    }

    @Test
    public void testCountAddressNumericGE1() {
        // RFC 5231 6. Example first example would evaluate to true
        String filterScript = "if address :count \"ge\" :comparator \"i;ascii-numeric\" "
                + "[\"to\", \"cc\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testCountAddressNumericGE2() {
        // RFC 5231 6. Example second example would evaluate to false
        String filterScript = "if anyof (address :count \"ge\" :comparator \"i;ascii-numeric\"\n"
                + "[\"to\"] [\"3\"],\n"
                + "address :count \"ge\" :comparator \"i;ascii-numeric\"\n"
                + "[\"cc\"] [\"3\"] )\n"
                + "{" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testCountHeaderNumericGE1() {
        // RFC 5231 6. Example third example would evaluate to false
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :count \"ge\" :comparator \"i;ascii-numeric\" "
                + "[\"received\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testCountHeaderNumericGE2() {
        // RFC 5231 6. Example fourth example would evaluate to true
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :count \"ge\" :comparator \"i;ascii-numeric\" "
                + "[\"received\", \"subject\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testCountHeaderNumericGE3() {
        // RFC 5231 6. Example fifth example would evaluate to false
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :count \"ge\" :comparator \"i;ascii-numeric\" "
                + "[\"to\", \"cc\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueAddressNumericGT() {
        // invalid comparison
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if address :value \"gt\" :comparator \"i;ascii-numeric\" "
                + "[\"to\"] [\"0\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueAddressCasemapGT() {
        // "from" address starts with 'N'-'Z'
        String filterScript = "if address :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"from\"] [\"M\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testValueHeaderNumericGT() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"gt\" :comparator \"i;ascii-numeric\" "
                + "[\"x-priority\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueHeadeNumericrLT() {
        // RFC 5231 7. Extended Example
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"lt\" :comparator \"i;ascii-numeric\" "
                + "[\"x-priority\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testValueHeaderNumericLE() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"le\" :comparator \"i;ascii-numeric\" "
                + "[\"x-priority\"] [\"3\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testValueHeaderNumericEQ() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"eq\" :comparator \"i;ascii-numeric\" "
                + "[\"x-priority\"] [\"1\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testValueHeaderNumericNE() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"ne\" :comparator \"i;ascii-numeric\" "
                + "[\"x-priority\"] [\"1\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueHeaderCasemapGT() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"from\"] [\"M\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testValueHeaderCasemapI18NGT() {
        // RFC 5231 7. Extended Example (modified)
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"subject\"] [\"か\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testBadFormat_nokeys() {
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if header :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"subject\"] :foo {" + "tag \"Priority\";}";
        /*
         * The following error will occur:
         * org.apache.jsieve.exception.SyntaxException: Expecting a StringList of keys Line 2 column 1.
         */
        doTest(filterScript, null);
    }

    @Test
    public void testBadFormat_noTestName() {
        String filterScript = "require [\"fileinto\", \"tag\", \"flag\", \"log\", \"relational\"];\n"
                + "if relational :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"subject\"] [\"test\"] {" + "tag \"Priority\";}";
        /*
         * The following error will occur:
         * org.apache.jsieve.exception.SyntaxException: Found unexpected arguments. Line 2 column 1.
         */
        doTest(filterScript, null);
    }

    @Test
    public void testCountEnvelopeToNumericEQ() {
        // RFC 5231 10. Security Considerations
        // An implementation MUST ensure that the test for envelope "to" only
        // reflects the delivery to the current user.
        String filterScript = "if envelope :count \"eq\" :comparator \"i;ascii-numeric\" "
                + "[\"TO\"] [\"1\"] {" + "tag \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    @Test
    public void testCountEnvelopeToNumericGT() {
        // RFC 5231 10. Security Considerations
        // An implementation MUST ensure that the test for envelope "to" only
        // reflects the delivery to the current user.  Using this test, it MUST
        // not be possible for a user to determine if this message was delivered
        // to someone else.
        // The number of sample LMTP RCPT TO addresses is 2, but the number of
        // address to be evaluated for the "envelope" test should be 1.
        String filterScript = "if envelope :count \"gt\" :comparator \"i;ascii-numeric\" "
                + "[\"to\"] [\"1\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueEnvelopeFromNumericGT() {
        // invalid comparison
        String filterScript = "if envelope :value \"gt\" :comparator \"i;ascii-numeric\" "
                + "[\"from\"] [\"1\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testValueEnvelopeCasemapGT() {
        // LMTP MAIL FROM envelope (<abc@zimbra.com>) does not start 'N'-'Z'
        String filterScript = "if envelope :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"from\"] [\"M\"] {" + "tag \"Priority\";}";
        doTest(filterScript, null);
    }

    @Test
    public void testBadFormat_invalidEnvHeaderName() {
        String filterScript = "if envelope :value \"gt\" :comparator \"i;ascii-casemap\" "
                + "[\"AUTH\"] [\"M\"] {" + "tag \"Priority\";}";
         // The following error will occur:
         // org.apache.jsieve.exception.SyntaxException: Unexpected header name as a value for <envelope-part>: 'AUTH'
        doTest(filterScript, null);
    }

    @Test
    public void testValueEnvelopeFromNumeric_AllUpperCase() {
        String filterScript = "IF ENVELOPE :COUNT \"EQ\" :COMPARATOR \"I;ASCII-NUMERIC\" "
                + "[\"TO\"] [\"1\"] {" + "TAG \"Priority\";}";
        doTest(filterScript, "Priority");
    }

    private void doTest(String filterScript, String expectedResult) {
        try {
            LmtpEnvelope env = setEnvelopeInfo();
            Account account = Provisioning.getInstance().getAccount(
                    MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            account.setMailSieveScript(filterScript);
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(
                    new OperationContext(mbox), mbox,
                    new ParsedMessage(sampleMsg.getBytes(), false), 0,
                    account.getName(), env,
                    new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals(expectedResult, ArrayUtil.getFirstElement(msg.getTags()));
        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    private LmtpEnvelope setEnvelopeInfo() {
        LmtpEnvelope env = new LmtpEnvelope();
        LmtpAddress sender = new LmtpAddress("<abc@zimbra.com>", new String[] { "BODY", "SIZE" }, null);
        LmtpAddress recipient1 = new LmtpAddress("<xyz@zimbra.com>", null, null);
        LmtpAddress recipient2 = new LmtpAddress("<uvw@zimbra.com>", null, null);
        env.setSender(sender);
        env.addLocalRecipient(recipient1);
        env.addLocalRecipient(recipient2);
        return env;
    }
}
