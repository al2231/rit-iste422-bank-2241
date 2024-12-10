import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

record BankRecords(Collection<Owner> owners, Collection<Account> accounts, Collection<RegisterEntry> registerEntries) { }

public class Obfuscator {
    private static Logger logger = LogManager.getLogger(Obfuscator.class.getName());
    private Random random = new Random();

    public BankRecords obfuscate(BankRecords rawObjects) {
        // Obfuscate and return the records
        // Example: mask SSN
        List<Owner> newOwners = new ArrayList<>();

        Map<String, String> nameMap = new HashMap<>();
        int counter = 1;
        for (Owner o : rawObjects.owners()) {
            String new_ssn = "***-**-" + o.ssn().substring(7);
            // other changes...

            // substitute Name
            String new_name = "Person " + counter++;
            nameMap.putIfAbsent(o.name(), new_name);

            Date new_dob = dateVariance(o.dob());

            newOwners.add(new Owner(new_name, o.id(), new_dob, new_ssn, o.address(), o.address2(), o.city(), o.state(), o.zip()));
        }
        Collection<Owner> obfuscatedOwners = newOwners;

        //obfuscate accounts
        List<Account> newAccounts = new ArrayList<>();

        for (Account a : rawObjects.accounts()) {
            // substitute Name
            String new_name = "Account " + a.getId();

            // number variance, offset ID
            long new_owner_id = 1000 + a.getOwnerId();

            Account newAccount;
            if (a instanceof SavingsAccount sa) {
                newAccount = new SavingsAccount(new_name, sa.getId(), sa.getBalance(), 0, new_owner_id);
            } else if (a instanceof CheckingAccount ca) {
                newAccount = new CheckingAccount(new_name, ca.getId(), ca.getBalance(), 0, new_owner_id);
            } else {
                newAccount = null;
            }
            newAccounts.add(newAccount);
        }
        Collection<Account> obfuscatedAccounts = newAccounts;

        //obfuscate registers
        List<RegisterEntry> newRegisterEntries = new ArrayList<>();

        for (RegisterEntry r : rawObjects.registerEntries()) {
            // amount number variance
            double new_amount = r.amount() + random.nextDouble() * 10 - 5;

            Date new_date = dateVariance(r.date());

            newRegisterEntries.add(new RegisterEntry(r.id(), r.accountId(), r.entryName(), new_amount, new_date));
        }
        Collection<RegisterEntry> obfuscatedRegisterEntries = newRegisterEntries;

        return new BankRecords(obfuscatedOwners, obfuscatedAccounts, obfuscatedRegisterEntries);
    }

    // helper method for date variance technique
    private Date dateVariance(Date dateIn) {
        int dayVariance = random.nextInt(61)- 30; //range of +- 30 days
        return new Date(dateIn.getTime() + (dayVariance * 24L * 60 * 60 * 1000));
    }

    /**
     * Change the integration test suite to point to our obfuscated production
     * records.
     *
     * To use the original integration test suite files run
     *   "git checkout -- src/test/resources/persister_integ.properties"
     */
    public void updateIntegProperties() throws IOException {
        Properties props = new Properties();
        File propsFile = new File("src/test/resources/persister_integ.properties".replace('/', File.separatorChar));
        if (! propsFile.exists() || !propsFile.canWrite()) {
            throw new RuntimeException("Properties file must exist and be writable: " + propsFile);
        }
        try (InputStream propsStream = new FileInputStream(propsFile)) {
            props.load(propsStream);
        }
        props.setProperty("persisted.suffix", "_prod");
        logger.info("Updating properties file '{}'", propsFile);
        try (OutputStream propsStream = new FileOutputStream(propsFile)) {
            String comment = String.format(
                    "Note: Don't check in changes to this file!!\n" +
                    "#Modified by %s\n" +
                    "#to reset run 'git checkout -- %s'",
                    this.getClass().getName(), propsFile);
            props.store(propsStream, comment);
        }
    }

    public static void main(String[] args) throws Exception {
        // enable assertions
        Obfuscator.class.getClassLoader().setClassAssertionStatus("Obfuscator", true);
        logger.info("Loading Production Records");
        Persister.setPersisterPropertiesFile("persister_prod.properties");
        Bank bank = new Bank();
        bank.loadAllRecords();

        logger.info("Obfuscating records");
        Obfuscator obfuscator = new Obfuscator();
        // Make a copy of original values so we can compare length
        // deep-copy collections so changes in obfuscator don't impact originals
        BankRecords originalRecords = new BankRecords(
                new ArrayList<>(bank.getAllOwners()),
                new ArrayList<>(bank.getAllAccounts()),
                new ArrayList<>(bank.getAllRegisterEntries()));
        BankRecords obfuscatedRecords = obfuscator.obfuscate(originalRecords);

        logger.info("Saving obfuscated records");
        obfuscator.updateIntegProperties();
        Persister.resetPersistedFileNameAndDir();
        Persister.setPersisterPropertiesFile("persister_integ.properties");
        // old version of file is cached so we need to override prefix (b/c file changed
        // is not the one on classpath)
        Persister.setPersistedFileSuffix("_prod");
        // writeReords is cribbed from Bank.saveALlRecords(), refactor into common
        // method?
        Persister.writeRecordsToCsv(obfuscatedRecords.owners(), "owners");
        Map<Class<? extends Account>, List<Account>> splitAccounts = obfuscatedRecords
                .accounts()
                .stream()
                .collect(Collectors.groupingBy(rec -> rec.getClass()));
        Persister.writeRecordsToCsv(splitAccounts.get(SavingsAccount.class), "savings");
        Persister.writeRecordsToCsv(splitAccounts.get(CheckingAccount.class),"checking");
        Persister.writeRecordsToCsv(obfuscatedRecords.registerEntries(), "register");

        logger.info("Original   record counts: {} owners, {} accounts, {} registers",
                originalRecords.owners().size(),
                originalRecords.accounts().size(),
                originalRecords.registerEntries().size());
        logger.info("Obfuscated record counts: {} owners, {} accounts, {} registers",
                obfuscatedRecords.owners().size(),
                obfuscatedRecords.accounts().size(),
                obfuscatedRecords.registerEntries().size());

        if (obfuscatedRecords.owners().size() != originalRecords.owners().size())
            throw new AssertionError("Owners count mismatch");
        if (obfuscatedRecords.accounts().size() != originalRecords.accounts().size())
            throw new AssertionError("Account count mismatch");
        if (obfuscatedRecords.registerEntries().size() != originalRecords.registerEntries().size())
            throw new AssertionError("RegisterEntries count mismatch");
    }
}
