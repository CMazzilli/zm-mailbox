package com.zimbra.qa.unittest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;

import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.PreAuthKey;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.AccountBy;
import com.zimbra.cs.account.Provisioning.DomainBy;
import com.zimbra.cs.account.ldap.LdapUtil;

import junit.framework.TestCase;
import junit.framework.TestSuite;

public class TestPreAuthServlet extends TestCase {

    String setUpDomain() throws Exception {
        String domainName = TestUtil.getDomain();
        Domain domain = Provisioning.getInstance().get(DomainBy.name, domainName);
        String preAuthKey = PreAuthKey.generateRandomPreAuthKey();
        Map<String, Object> attrs = new HashMap<String, Object>();
        attrs.put(Provisioning.A_zimbraPreAuthKey, preAuthKey);
        Provisioning.getInstance().modifyAttrs(domain, attrs);
        return preAuthKey;
    }
    
    String genPreAuthUrl(String preAuthKey, String user, boolean shouldFail) throws Exception {
        
        HashMap<String,String> params = new HashMap<String,String>();
        String acctName = TestUtil.getAddress(user);
        String authBy = "name";
        long timestamp = System.currentTimeMillis();
        long expires = 0;
        
        params.put("account", acctName);
        params.put("by", authBy);
        params.put("timestamp", timestamp+"");
        params.put("expires", expires+"");
        String preAuth = PreAuthKey.computePreAuth(params, preAuthKey);
        
        StringBuffer url = new StringBuffer("/service/preauth?");
        url.append("account=" + acctName);
        url.append("&by=" + authBy);
        if (shouldFail) {
            // if doing negative testing, mess up with the timestamp so
            // it won't be computed to the same value at the server and 
            // the preauth will fail
            long timestampBad = timestamp + 10;
            url.append("&timestamp=" + timestampBad);
        } else    
            url.append("&timestamp=" + timestamp);
        url.append("&expires=" + expires);
        url.append("&preauth=" + preAuth);
        
        return url.toString();
    }
    
    void doPreAuthServletRequest(String preAuthUrl) throws Exception{
        int port = port = Provisioning.getInstance().getLocalServer().getIntAttr(Provisioning.A_zimbraMailPort, 0);
        String url = "http://localhost:" + port + preAuthUrl;
        
        HttpClient client = new HttpClient();
        HttpMethod method = new GetMethod(url);
        
        try {
            int respCode = client.executeMethod(method);
            int statusCode = method.getStatusCode();
            String statusLine = method.getStatusLine().toString();
            
            System.out.println("respCode=" + respCode);
            System.out.println("statusCode=" + statusCode);
            System.out.println("statusLine=" + statusLine);
            
            /*
            System.out.println("Headers");
            Header[] respHeaders = method.getResponseHeaders();
            for (int i=0; i < respHeaders.length; i++) {
                String header = respHeaders[i].toString();
                System.out.println(header);
            }
            
            String respBody = method.getResponseBodyAsString();
            // System.out.println("respBody=" + respBody);
            */
            
        } catch (HttpException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } finally {
            method.releaseConnection();
        }
    }
    
    private void doPreAuth(String userLocalPart, boolean shouldFail) throws Exception {
        String preAuthKey = setUpDomain();
        String preAuthUrl = genPreAuthUrl(preAuthKey, userLocalPart, shouldFail);
        
        System.out.println("preAuthKey=" + preAuthKey);
        System.out.println("preAuth=" + preAuthUrl);
        
        doPreAuthServletRequest(preAuthUrl);
    }
    
    public void disable_testPreAuthServlet() throws Exception {
        doPreAuth("user1", false);
    }
    
    private Account dumpLockoutAttrs(String user) throws Exception {
        Account acct = Provisioning.getInstance().get(AccountBy.name, user);
        
        System.out.println();
        System.out.println(Provisioning.A_zimbraAccountStatus + ": " + acct.getAttr(Provisioning.A_zimbraAccountStatus));
        System.out.println(Provisioning.A_zimbraPasswordLockoutLockedTime + ": " + acct.getAttr(Provisioning.A_zimbraPasswordLockoutLockedTime));

        System.out.println(Provisioning.A_zimbraPasswordLockoutFailureTime + ": ");
        String[] failureTime = acct.getMultiAttr(Provisioning.A_zimbraPasswordLockoutFailureTime);
        for (String ft : failureTime)
            System.out.println("    " + ft);
        
        return acct;
    }
    
    public void testPreAuthLockout() throws Exception {
        String user = "user4";
        Account acct = TestUtil.getAccount(user);
        
        Provisioning prov = Provisioning.getInstance();
        
        Map<String, Object> attrs = new HashMap<String, Object>();
        
        int lockoutAfterNumFailures = 3;
        
        // setup lockout config attrs
        attrs.put(Provisioning.A_zimbraPasswordLockoutEnabled, LdapUtil.LDAP_TRUE);
        attrs.put(Provisioning.A_zimbraPasswordLockoutDuration, "1m");
        attrs.put(Provisioning.A_zimbraPasswordLockoutMaxFailures, lockoutAfterNumFailures+"");
        attrs.put(Provisioning.A_zimbraPasswordLockoutFailureLifetime, "30s");
        
        // put the account in active mode, clean all lockout attrs that might have been set 
        // in previous test
        attrs.put(Provisioning.A_zimbraAccountStatus, "active");
        attrs.put(Provisioning.A_zimbraPasswordLockoutLockedTime, "");
        attrs.put(Provisioning.A_zimbraPasswordLockoutFailureTime, "");
        
        prov.modifyAttrs(acct, attrs);
        
        System.out.println("Before the test:");
        dumpLockoutAttrs(user);
        System.out.println();
        
        // the account should be locked out at the last iteration
        for (int i=0; i<=lockoutAfterNumFailures; i++) {
            System.out.println("======================");
            System.out.println("Iteration: " + i);
            
            doPreAuth(user, true);
            Account a = dumpLockoutAttrs(user);
            System.out.println("\n\n");
            
            if (i >= lockoutAfterNumFailures-1)
                assertEquals("lockout", a.getAttr(Provisioning.A_zimbraAccountStatus));
            else
                assertEquals("active", a.getAttr(Provisioning.A_zimbraAccountStatus));
            
            // sleep two seconds
            Thread.sleep(2000);
        }
        
        /*
        zimbraPasswordLockoutDuration: 3m
        zimbraPasswordLockoutEnabled: TRUE
        zimbraPasswordLockoutFailureLifetime: 1m
        zimbraPasswordLockoutMaxFailures: 5

        <attr id="378" name="zimbraPasswordLockoutEnabled" type="boolean" cardinality="single" optionalIn="account,cos" flags="accountInherited,domainAdminModifiable">
        <attr id="379" name="zimbraPasswordLockoutDuration" type="duration" cardinality="single" optionalIn="account,cos" flags="accountInherited,domainAdminModifiable">
        <attr id="380" name="zimbraPasswordLockoutMaxFailures" type="integer" cardinality="single" optionalIn="account,cos" flags="accountInherited,domainAdminModifiable">
        <attr id="381" name="zimbraPasswordLockoutFailureLifetime" type="duration" cardinality="single" optionalIn="account,cos" flags="accountInherited,domainAdminModifiable">
        <attr id="382" name="zimbraPasswordLockoutLockedTime" type="gentime" cardinality="single" optionalIn="account" flags="domainAdminModifiable">
        <attr id="383" name="zimbraPasswordLockoutFailureTime" type="gentime" cardinality="multi" optionalIn="account" flags="domainAdminModifiable">
        */
    }
    
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception{
        TestUtil.cliSetup();
        try {
            TestUtil.runTest(new TestSuite(TestPreAuthServlet.class));
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

}
