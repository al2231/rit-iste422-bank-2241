import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collector;
import java.util.stream.Collectors;

record BankRecords(Collection<Owner> owners, Collection<Account> accounts, Collection<RegisterEntry> registerEntries) { }

public class Obfuscator {
    private static Logger logger = LogManager.getLogger(Obfuscator.class.getName());
    private Random random = new Random();

    public BankRecords obfuscate(BankRecords rawObjects) {
        // Obfuscate and return the records
        Map<Long, Long> ownerIdMap = new HashMap<>();
        Map<Long, Long> accountIdMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();

        // Example: mask SSN
        List<Owner> newOwners = new ArrayList<>();

        int counter = 1;
        for (Owner o : rawObjects.owners()) {
            long new_owner_id = 1000 + o.id(); //offset owner id to match accounts
            ownerIdMap.put(o.id(), new_owner_id);

            String new_ssn = "***-**-" + o.ssn().substring(7);
            // other changes...

            // substitute Name
            String new_name = "Person " + counter++;
            nameMap.putIfAbsent(o.name(), new_name);

            Date new_dob = dateVariance(o.dob());

            newOwners.add(new Owner(new_name, new_owner_id, new_dob, new_ssn, o.address(), o.address2(), o.city(), o.state(), o.zip()));
        }
        Collection<Owner> obfuscatedOwners = newOwners;

        //obfuscate accounts
        List<Account> newAccounts = new ArrayList<>();

        for (Account a : rawObjects.accounts()) {
            // number variance, offset ID
            long new_owner_id = ownerIdMap.get(a.getOwnerId());
            long new_account_id = 2000 + a.getId(); 
            accountIdMap.put(a.getId(), new_account_id);

            // substitute Name
            String new_name = "Account " + new_account_id;

            Account newAccount;
            if (a instanceof SavingsAccount sa) {
                newAccount = new SavingsAccount(new_name, new_account_id, sa.getBalance(), 0, new_owner_id);
            } else if (a instanceof CheckingAccount ca) {
                newAccount = new CheckingAccount(new_name, new_account_id, ca.getBalance(), 0, new_owner_id);
            } else {
                throw new IllegalStateException("Unexpected account type");
            }
            newAccounts.add(newAccount);
        }
        Collection<Account> obfuscatedAccounts = newAccounts;

        //obfuscate registers
        List<RegisterEntry> newRegisterEntries = new ArrayList<>();

        Map<Long, Double> accountBalances = new HashMap<>();

        //shuffle amount
        // List<Double> originalAmounts = copiedEntries.stream()
        //     .map(RegisterEntry::amount)
        //     .collect(Collectors.toList());
        // Collections.shuffle(originalAmounts);
        // int id = 0;
        for (RegisterEntry r : rawObjects.registerEntries()) {
            long new_account_id = accountIdMap.get(r.accountId());

            // double new_amount = originalAmounts.get(id++);
            double new_amount = r.amount() + random.nextDouble() * 10 - 5;
            accountBalances.put(new_account_id, accountBalances.getOrDefault(new_account_id, 0.0) + new_amount);

            Date new_date = dateVariance(r.date());

            newRegisterEntries.add(new RegisterEntry(r.id(), new_account_id, r.entryName(), new_amount, new_date));
        }
        Collection<RegisterEntry> obfuscatedRegisterEntries = newRegisterEntries;

        // verify balance of each account
        for (Account a : rawObjects.accounts()) {
            long accountId = accountIdMap.get(a.getId());
            // long accountId = a.getId();
            double totalRegisterAmount = accountBalances.getOrDefault(accountId, 0.0);

            // logger.info("Account {} balance: {}", accountId, totalRegisterAmount);

            if (a instanceof SavingsAccount sa) {
                sa.setBalance(totalRegisterAmount);
            } else if (a instanceof CheckingAccount ca) {
                ca.setBalance(totalRegisterAmount);
            }
        }
        
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
