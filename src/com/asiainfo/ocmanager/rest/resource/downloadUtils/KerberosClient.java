package com.asiainfo.ocmanager.rest.resource.downloadUtils;

import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.config.ClusterConfig;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.exception.*;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.utils.ShellCommandUtil;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.directory.server.kerberos.shared.crypto.encryption.KerberosKeyFactory;
import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.apache.directory.server.kerberos.shared.keytab.KeytabEntry;
import org.apache.directory.shared.kerberos.KerberosTime;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.apache.directory.shared.kerberos.components.EncryptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
/**
 * Created by zhangfq on 2017/8/28.
 */
public class KerberosClient {

    private Logger logger = LoggerFactory.getLogger(KerberosClient.class);
    private static final Set<EncryptionType> DEFAULT_CIPHERS = Collections.unmodifiableSet(
        new HashSet<EncryptionType>() {{
            add(EncryptionType.DES_CBC_MD5);
            add(EncryptionType.DES3_CBC_SHA1_KD);
            add(EncryptionType.RC4_HMAC);
            add(EncryptionType.AES128_CTS_HMAC_SHA1_96);
            add(EncryptionType.AES256_CTS_HMAC_SHA1_96);
        }});
    private Set<EncryptionType> ciphers = new HashSet<EncryptionType>(DEFAULT_CIPHERS);
    private String userPrincipal;
    private String keytabLocation;
    private String adminPwd;
    private String kdcHost;
    private String realm;
    private static final Pattern PATTERN_GET_KEY_NUMBER = Pattern.compile("^.*?Key: vno (\\d+).*$", 32);

    public KerberosClient(ClusterConfig clusterConfig){
        this.userPrincipal = clusterConfig.getKrbUserPrincipal();
        this.keytabLocation = clusterConfig.getKrbKeytabLocation();
        this.adminPwd = clusterConfig.getKrbAdminPwd();
        this.kdcHost = clusterConfig.getKrbKdcHost();
        this.realm = clusterConfig.getKrbRealm();
    }

    public KerberosClient(Map<String,String> keytabConfig){
        this.userPrincipal = keytabConfig.get("userPrincipal");
        this.keytabLocation = keytabConfig.get("keytabLocation");
        this.adminPwd = keytabConfig.get("adminPwd");
        this.kdcHost = keytabConfig.get("kdcHost");
        this.realm = keytabConfig.get("realm");
    }
    /**
     * Invokes the kadmin shell command to issue queries
     *
     * @param query a String containing the query to send to the kdamin command
     * @return a ShellCommandUtil.Result containing the result of the operation
     * @throws KerberosKDCConnectionException       if a connection to the KDC cannot be made
     * @throws KerberosAdminAuthenticationException if the administrator credentials fail to authenticate
     * @throws KerberosRealmException               if the realm does not map to a KDC
     * @throws KerberosOperationException           if an unexpected error occurred
     */
    public ShellCommandUtil.Result invokeKAdmin(String query)
        throws KerberosOperationException {
        if (StringUtils.isEmpty(query)) {
            throw new KerberosOperationException("Missing kadmin query");
        }
        ShellCommandUtil.Result result;
        String defaultRealm = this.realm;

        List<String> command = new ArrayList<String>();

        String adminPrincipal = this.userPrincipal;

        String adminPassword = this.adminPwd;

        // Set the kdamin interface to be kadmin
        command.add("/usr/bin/kadmin");

        // Add explicit KDC admin host, if available
        String kdcHost = this.kdcHost;
        if (kdcHost != null) {
            command.add("-s");
            command.add(kdcHost);
        }

        // Add the administrative principal
        command.add("-p");
        command.add(adminPrincipal);

        if (adminPassword != null) {
            // Add password for administrative principal
            command.add("-w");
            command.add(adminPassword);
        }

        if (!StringUtils.isEmpty(defaultRealm)) {
            // Add default realm clause
            command.add("-r");
            command.add(defaultRealm);
        }

        // Add kadmin query
        command.add("-q");
        command.add(query);

        result = executeCommand(command.toArray(new String[command.size()]), null);

        if (!result.isSuccessful()) {
            // Test STDERR to see of any "expected" error conditions were encountered...
            String stdErr = result.getStderr();
            // Did admin credentials fail?
            if (stdErr.contains("Client not found in Kerberos database")) {
                throw new KerberosAdminAuthenticationException(stdErr);
            } else if (stdErr.contains("Incorrect password while initializing")) {
                throw new KerberosAdminAuthenticationException(stdErr);
            }
            // Did we fail to connect to the KDC?
            else if (stdErr.contains("Cannot contact any KDC")) {
                throw new KerberosKDCConnectionException(stdErr);
            } else if (stdErr.contains("Cannot resolve network address for admin server in requested realm while initializing kadmin interface")) {
                throw new KerberosKDCConnectionException(stdErr);
            }
            // Was the realm invalid?
            else if (stdErr.contains("Missing parameters in krb5.conf required for kadmin client")) {
                throw new KerberosRealmException(stdErr);
            } else if (stdErr.contains("Cannot find KDC for requested realm while initializing kadmin interface")) {
                throw new KerberosRealmException(stdErr);
            } else {
                throw new KerberosOperationException("Unexpected error condition executing the kadmin command");
            }
        }

        return result;
    }

    /**
     * Creates a new principal in a previously configured MIT KDC
     * <p/>
     * This implementation creates a query to send to the kadmin shell command and then interrogates
     * the result from STDOUT to determine if the operation executed successfully.
     *
     * @param principal a String containing the principal add
     * @param password  a String containing the password to use when creating the principal
     * @return an Integer declaring the generated key number
     * @throws KerberosOperationException           if an unexpected error occurred
     */
    public void createPrincipal(String principal, String password)
        throws KerberosOperationException{
        if (StringUtils.isEmpty(principal)) {
            throw new KerberosOperationException("Failed to create new principal - no principal specified");
        } else if (StringUtils.isEmpty(password)) {
            throw new KerberosOperationException("Failed to create new principal - no password specified");
        } else {
            // Create the kdamin query:  add_principal <-randkey|-pw <password>> [<options>] <principal>
            ShellCommandUtil.Result result = invokeKAdmin(String.format("add_principal -pw \"%s\" %s",
                password, principal));

            // If there is data from STDOUT, see if the following string exists:
            //    Principal "<principal>" created
            String stdOut = result.getStdout();
            if((stdOut == null) || (! stdOut.contains(String.format("Principal \"%s\" created", principal)))){
                throw new KerberosOperationException(String.format("Failed to create service principal for %s\nSTDOUT: %s\nSTDERR: %s",
                    principal, stdOut, result.getStderr()));
            }
        }
    }

    /**
     * Test to see if the specified principal exists in a previously configured MIT KDC
     * <p/>
     * This implementation creates a query to send to the kadmin shell command and then interrogates
     * the result from STDOUT to determine if the presence of the specified principal.
     *
     * @param principal a String containing the principal to test
     * @return true if the principal exists; false otherwise
     * @throws KerberosOperationException           if an unexpected error occurred
     */
    public boolean principalExists(String principal)
        throws KerberosOperationException {
        if (principal == null) {
            return false;
        } else {
            // Create the KAdmin query to execute:
            ShellCommandUtil.Result result = invokeKAdmin(String.format("get_principal %s", principal));

            // If there is data from STDOUT, see if the following string exists:
            //    Principal: <principal>
            String stdOut = result.getStdout();
            return (stdOut != null) && stdOut.contains(String.format("Principal: %s", principal));
        }
    }

    /**
     * Removes an existing principal in a previously configured KDC
     * <p/>
     * The implementation is specific to a particular type of KDC.
     *
     * @param principal a String containing the principal to remove
     * @return true if the principal was successfully removed; otherwise false
     * @throws KerberosOperationException           if an unexpected error occurred
     */
    public boolean removePrincipal(String principal)
        throws KerberosOperationException{
        if (StringUtils.isEmpty(principal)) {
            throw new KerberosOperationException("Failed to remove new principal - no principal specified");
        } else {
            ShellCommandUtil.Result result = invokeKAdmin(String.format("delete_principal -force %s", principal));

            // If there is data from STDOUT, see if the following string exists:
            //    Principal "<principal>" created
            String stdOut = result.getStdout();
            return (stdOut != null) && !stdOut.contains("Principal does not exist");
        }
    }

    /**
     * Create a keytab using the specified principal and password.
     *
     * @param principal a String containing the principal to test
     * @param password  a String containing the password to use when creating the principal
    //     * @param keyNumber a Integer indicating the key number for the keytab entries
     * @return the created Keytab
     * @throws KerberosOperationException
     */
    public  Keytab createKeyTab(String principal, String password, Integer keyNumber)
        throws KerberosOperationException {
        if (StringUtils.isEmpty(principal)) {
            throw new KerberosOperationException("Failed to create keytab file, missing principal");
        }

        if (password == null) {
            throw new KerberosOperationException(String.format("Failed to create keytab file for %s, missing password", principal));
        }

        List<KeytabEntry> keytabEntries = new ArrayList<KeytabEntry>();
        Keytab keytab = new Keytab();


        if (!ciphers.isEmpty()) {
            // Create a set of keys and relevant keytab entries
            Map<EncryptionType, EncryptionKey> keys = KerberosKeyFactory.getKerberosKeys(principal, password, ciphers);

            if (keys != null) {
//                Integer keyNumber = getKeyNumber(principal);
                byte keyVersion = (keyNumber == null) ? 0 : keyNumber.byteValue();
                KerberosTime timestamp = new KerberosTime();

                for (EncryptionKey encryptionKey : keys.values()) {
                    keytabEntries.add(new KeytabEntry(principal, 1, timestamp, keyVersion, encryptionKey));
                }

                keytab.setEntries(keytabEntries);
            }
        }

        return keytab;
    }

    /**
     * Create a keytab file using the specified Keytab
     * <p/>
     * @param keytab                the Keytab containing the data to add to the keytab file
     * @param keytabFilePath a File containing the absolute path to where the keytab data is to be stored
     * @return true if the keytab file was successfully created; false otherwise
     */
    public File createKeyTabFile(Keytab keytab, String keytabFilePath)
        throws KerberosOperationException{
        Map result = new HashMap<>();
        if (keytabFilePath == null)
        {
            throw new KerberosOperationException("The destination file path is null.");
        }
        File keytabFile = new File(keytabFilePath);
        try{
            keytab.write(keytabFile);
        }catch (IOException e){
            throw new KerberosOperationException("Fail to export keytab file", e);
        }
        return keytabFile;
    }

//    /**
//     * Create a keytab string using the base64 encode
//     * <p/>
//     * @param principal a String containing the principal to test
//     * @param password  a String containing the password to use when creating the principal
//     * @param keyNumber a Integer indicating the key number for the keytab entries
//     * @return a keytab string using the base64 encode if keytab was successfully created; empty string otherwise
//     */
//    public  String createKeyTabString(String principal, String password, Integer keyNumber){
//        String keyTabString = "";
//        try{
//            Keytab keytab = this.createKeyTab(principal, password, keyNumber);
//            KeytabEncoder keytabEncoder = new KeytabEncoder();
//            ByteBuffer keytabByteBuffer = keytabEncoder.write(keytab.getKeytabVersion(), keytab.getEntries());
//            keyTabString = Base64.encodeBase64String(keytabByteBuffer.array());
//        }catch (KerberosOperationException e){
//            e.printStackTrace();
//        }
//        return keyTabString;
//    }

    private  void ensureKeytabFolderExists(String keytabFilePath) {
        String keytabFolderPath = keytabFilePath.substring(0, keytabFilePath.lastIndexOf("/"));
        File keytabFolder = new File(keytabFolderPath);
        if (!keytabFolder.exists() || !keytabFolder.isDirectory()) {
            keytabFolder.mkdir();
        }
    }

    public Integer getKeyNumber(String principal)
        throws KerberosOperationException
    {
        if (StringUtils.isEmpty(principal)) {
            throw new KerberosOperationException("Failed to get key number for principal  - no principal specified");
        }
        ShellCommandUtil.Result result = invokeKAdmin(String.format("get_principal %s", new Object[] { principal }));

        String stdOut = result.getStdout();
        if (stdOut == null)
        {
            String message = String.format("Failed to get key number for %s:\n\tExitCode: %s\n\tSTDOUT: NULL\n\tSTDERR: %s", new Object[] { principal,
                Integer.valueOf(result.getExitCode()), result.getStderr() });
            this.logger.warn(message);
            throw new KerberosOperationException(message);
        }
        Matcher matcher = PATTERN_GET_KEY_NUMBER.matcher(stdOut);
        if (matcher.matches())
        {
            NumberFormat numberFormat = NumberFormat.getIntegerInstance();
            String keyNumber = matcher.group(1);

            numberFormat.setGroupingUsed(false);
            try
            {
                Number number = numberFormat.parse(keyNumber);
                this.logger.info("Get key number for principal " + principal + "is " + keyNumber + ".");
                return Integer.valueOf(number == null ? 0 : number.intValue());
            }
            catch (ParseException e)
            {
                String message = String.format("Failed to get key number for %s - invalid key number value (%s):\n\tExitCode: %s\n\tSTDOUT: NULL\n\tSTDERR: %s", new Object[] { principal, keyNumber,
                    Integer.valueOf(result.getExitCode()), result.getStderr() });
                this.logger.warn(message);
                throw new KerberosOperationException(message);
            }
        }
        String message = String.format("Failed to get key number for %s - unexpected STDOUT data:\n\tExitCode: %s\n\tSTDOUT: NULL\n\tSTDERR: %s", new Object[] { principal,
            Integer.valueOf(result.getExitCode()), result.getStderr() });
        this.logger.warn(message);
        throw new KerberosOperationException(message);
    }
    protected ShellCommandUtil.Result executeCommand(String[] command, Map<String, String> envp)
        throws KerberosOperationException {

        if ((command == null) || (command.length == 0)) {
            return null;
        } else {
            try {
                return ShellCommandUtil.runCommand(command, envp);
            } catch (IOException e) {
                String message = String.format("Failed to execute the command: %s", e.getLocalizedMessage());
                logger.warn(message);
                throw new KerberosOperationException(message, e);
            } catch (InterruptedException e) {
                String message = String.format("Failed to wait for the command to complete: %s", e.getLocalizedMessage());
                logger.warn(message);
                throw new KerberosOperationException(message, e);
            }
        }
    }

//    public void createKeyTab(String principal,String password,String keytabFilePath,String adminPwd,String kdcHost,String realm){
//        Keytab keytab = new Keytab();
//        try{
//            Map<String,String> config = new HashMap<>();
//            config.put("userPrincipal",principal);
//            config.put("keytabLocation",keytabFilePath);
//            config.put("adminPwd",adminPwd);
//            config.put("kdcHost",kdcHost);
//            config.put("realm",realm);
//            KerberosClient krb = new KerberosClient(config);
////            krb.createPrincipal("wangwang1@CITIC.COM","123456");
//            keytab = krb.createKeyTab(principal,password, 0);
//            File result =  krb.createKeyTabFile(keytab,keytabFilePath);
//            System.out.println("result is :" +String.valueOf(result));
//        }catch (Exception e){
//            System.out.println(e);
//        }
//
//    }
}
