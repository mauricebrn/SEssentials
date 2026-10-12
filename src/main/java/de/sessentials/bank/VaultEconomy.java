package de.sessentials.bank;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.List;

/**
 * Stellt die Bank anderen Plugins (Shops, Jobs, …) über Vault bereit. Beträge sind ganze Einheiten;
 * Kommazahlen von anderen Plugins werden abgerundet. Welt-Varianten ignorieren die Welt, Gemeinschaftskonten
 * ("Banks" im Vault-Sinn) werden nicht unterstützt.
 */
public final class VaultEconomy implements Economy {

    private final BankManager bank;

    private VaultEconomy(BankManager bank) {
        this.bank = bank;
    }

    /** Nur aufrufen, wenn Vault installiert ist – sonst fehlen die Vault-Klassen. */
    public static void register(Plugin plugin, BankManager bank) {
        Bukkit.getServicesManager().register(Economy.class, new VaultEconomy(bank), plugin, ServicePriority.Normal);
    }

    private static long units(double amount) {
        return (long) Math.floor(amount);
    }

    private EconomyResponse ok(OfflinePlayer player, long amount) {
        return new EconomyResponse(amount, bank.balance(player.getUniqueId()), ResponseType.SUCCESS, null);
    }

    private EconomyResponse fail(OfflinePlayer player, String error) {
        return new EconomyResponse(0, bank.balance(player.getUniqueId()), ResponseType.FAILURE, error);
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer byName(String name) {
        return Bukkit.getOfflinePlayer(name);
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "SEssentials"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 0; }
    @Override public String format(double amount) { return bank.format(units(amount)); }
    @Override public String currencyNamePlural() { return bank.currencyName(2); }
    @Override public String currencyNameSingular() { return bank.currencyName(1); }

    @Override public boolean hasAccount(OfflinePlayer player) { return bank.hasAccount(player.getUniqueId()); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return hasAccount(player); }
    @Override public boolean hasAccount(String name) { return hasAccount(byName(name)); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(byName(name)); }

    @Override public double getBalance(OfflinePlayer player) { return bank.balance(player.getUniqueId()); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
    @Override public double getBalance(String name) { return getBalance(byName(name)); }
    @Override public double getBalance(String name, String world) { return getBalance(byName(name)); }

    @Override public boolean has(OfflinePlayer player, double amount) { return bank.balance(player.getUniqueId()) >= amount; }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }
    @Override public boolean has(String name, double amount) { return has(byName(name), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(byName(name), amount); }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) return fail(player, "Negativer Betrag");
        // aufrunden: wer 12,5 abbuchen will, zahlt 13 – nie weniger als verlangt
        long units = (long) Math.ceil(amount);
        bank.ensureAccount(player);
        if (!bank.withdraw(player.getUniqueId(), units)) return fail(player, "Nicht genug Guthaben");
        return ok(player, units);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }
    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdrawPlayer(byName(name), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(byName(name), amount); }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) return fail(player, "Negativer Betrag");
        long units = units(amount);
        bank.ensureAccount(player);
        bank.deposit(player.getUniqueId(), units);
        return ok(player, units);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return depositPlayer(byName(name), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(byName(name), amount); }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        boolean existed = bank.hasAccount(player.getUniqueId());
        bank.ensureAccount(player);
        return !existed;
    }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return createPlayerAccount(player); }
    @Override public boolean createPlayerAccount(String name) { return createPlayerAccount(byName(name)); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(byName(name)); }

    // Gemeinschaftskonten werden nicht unterstützt
    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "SEssentials unterstützt keine Gemeinschaftskonten");
    }
    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }
}
