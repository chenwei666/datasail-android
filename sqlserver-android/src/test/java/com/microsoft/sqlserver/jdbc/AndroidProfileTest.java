package com.microsoft.sqlserver.jdbc;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.cert.*;
import javax.xml.transform.stax.StAXResult;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;

public class AndroidProfileTest {
    private long cap() { return (long) (0.9 * Runtime.getRuntime().maxMemory()); }
    @Test public void absentLimitKeepsUpstreamSentinel() throws Exception {
        assertEquals(-1, MaxResultBufferParser.validateMaxResultBuffer(null));
        assertEquals(-1, MaxResultBufferParser.validateMaxResultBuffer(""));
        assertEquals(-1, MaxResultBufferParser.validateMaxResultBuffer("-1"));
    }
    @Test public void explicitBudgetUsesDecimalBytesAndHeapCeiling() throws Exception {
        assertEquals(Math.min(8_000_000L, cap()), MaxResultBufferParser.validateMaxResultBuffer("8M"));
        assertEquals(Math.min(1000L, cap()), MaxResultBufferParser.validateMaxResultBuffer("1K"));
    }
    @Test public void percentagesUseRuntimeHeapWithoutJmx() throws Exception {
        assertEquals((long) (0.5 * Runtime.getRuntime().maxMemory()), MaxResultBufferParser.validateMaxResultBuffer("50pct"));
        assertEquals(cap(), MaxResultBufferParser.validateMaxResultBuffer("100percent"));
    }
    @Test public void largeMultipliersCannotWrapToNegativeOrSmallLimits() throws Exception {
        for (String suffix : new String[] {"K", "M", "G", "T"})
            assertEquals(cap(), MaxResultBufferParser.validateMaxResultBuffer(Long.MAX_VALUE + suffix));
    }
    @Test public void malformedAndNonPositiveLimitsReject() {
        for (String input : new String[] {"0", "-2", "-1M", "1.5M", "999999999999999999999", "one"})
            assertThrows(SQLServerException.class, () -> MaxResultBufferParser.validateMaxResultBuffer(input));
    }
    private X509Certificate cert(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + name + ".crt")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }
    @Test public void matchingSanWorksDespiteDifferentCommonName() throws Exception {
        SQLServerCertificateUtils.validateServerNameInCertificate(cert("san-localhost"), "localhost");
    }
    @Test public void commonNameAloneCannotAuthenticateServer() throws Exception {
        X509Certificate certificate = cert("cn-only");
        assertThrows(CertificateException.class, () -> SQLServerCertificateUtils.validateServerNameInCertificate(certificate, "localhost"));
    }
    @Test public void matchingCommonNameCannotOverrideWrongSan() throws Exception {
        X509Certificate certificate = cert("san-mismatch");
        assertThrows(CertificateException.class, () -> SQLServerCertificateUtils.validateServerNameInCertificate(certificate, "localhost"));
    }
    @Test public void pkcs1KeysFailExplicitlyWithoutLoadingOptionalCrypto() throws Exception {
        java.lang.reflect.Method method = SQLServerCertificateUtils.class.getDeclaredMethod("loadPrivateKeyFromPKCS1", String.class, String.class);
        method.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> method.invoke(null, "synthetic invalid key", null));
        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("not supported"));
    }
    @Test public void staxRequestsFailRatherThanReturningEmptySuccess() {
        assertThrows(SQLServerException.class, () -> new SQLServerSQLXML(null).setResult(StAXResult.class));
        assertThrows(SQLServerException.class, () -> new SQLServerSQLXML(null).getSourceInternal(StAXSource.class));
    }
    @Test public void supportedXmlStreamStillPreservesContentAndSingleUse() throws Exception {
        SQLServerSQLXML xml = new SQLServerSQLXML(null);
        StreamResult result = xml.setResult(StreamResult.class);
        byte[] expected = "<r>数舟 &amp; 00123</r>".getBytes(StandardCharsets.UTF_8);
        result.getOutputStream().write(expected);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        try (InputStream input = xml.getValue()) { int next; while ((next = input.read()) != -1) actual.write(next); }
        assertArrayEquals(expected, actual.toByteArray());
        assertThrows(SQLServerException.class, xml::getValue);
    }
}
