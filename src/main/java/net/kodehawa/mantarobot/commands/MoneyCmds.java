/*
 * Copyright (C) 2016-2017 David Alejandro Rubio Escares / Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import br.com.brjdevs.java.utils.texts.StringUtils;
import com.google.common.eventbus.Subscribe;
import com.jagrosh.jdautilities.utils.FinderUtil;
import com.rethinkdb.gen.ast.OrderBy;
import com.rethinkdb.model.OptArgs;
import com.rethinkdb.net.Cursor;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.RateLimiter;
import net.kodehawa.mantarobot.commands.currency.TextChannelGround;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.Items;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.InteractiveOperation;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.helpers.PlayerData;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import org.apache.commons.lang3.tuple.Pair;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.rethinkdb.RethinkDB.r;
import static net.kodehawa.mantarobot.utils.Utils.handleDefaultRatelimit;

/**
 * Basically part of CurrencyCmds, but only the money commands.
 */
@Module
public class MoneyCmds {

    private final Random random = new Random();
    private final int SLOTS_MAX_MONEY = 100_000_000;

    @Subscribe
    public void daily(CommandRegistry cr) {
        final RateLimiter rateLimiter = new RateLimiter(TimeUnit.HOURS, 24);
        Random r = new Random();
        cr.register("daily", new SimpleCommand(Category.CURRENCY) {
            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {
                long money = 150L;
                User mentionedUser = null;
                try {
                    mentionedUser = event.getMessage().getMentionedUsers().get(0);
                } catch(IndexOutOfBoundsException ignored) {}

                Player player = mentionedUser != null ? MantaroData.db().getPlayer(event.getGuild().getMember(mentionedUser)) : MantaroData.db().getPlayer(event.getMember());

                if(player.isLocked()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + (mentionedUser != null ? "That user cannot receive daily credits now." : "You cannot get daily credits now.")).queue();
                    return;
                }

                if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event)) return;

                if(mentionedUser != null && !mentionedUser.getId().equals(event.getAuthor().getId())) {
                    money = money + r.nextInt(2);

                    if(player.getInventory().containsItem(Items.COMPANION)) money = Math.round(money + (money * 0.10));

                    if(mentionedUser.getId().equals(player.getData().getMarriedWith()) && player.getData().getMarriedSince() != null &&
                            Long.parseLong(player.getData().anniversary()) - player.getData().getMarriedSince() > TimeUnit.DAYS.toMillis(1)) {
                        money = money + r.nextInt(20);

                        if(player.getInventory().containsItem(Items.RING_2)) {
                            money = money + r.nextInt(10);
                        }
                    }

                    player.addMoney(money);
                    player.save();

                    event.getChannel().sendMessage(EmoteReference.CORRECT + "I gave your **$" + money + "** daily credits to " +
                            mentionedUser.getName()).queue();
                    return;
                }

                PlayerData playerData = player.getData();

                String streak;
                if(System.currentTimeMillis() - playerData.getLastDailyAt() < TimeUnit.DAYS.toMillis(2)) {
                    playerData.setDailyStrike(playerData.getDailyStrike() + 1);
                    streak = "Streak up! Current streak: `" + playerData.getDailyStrike() + "x`";
                } else {
                    if(playerData.getDailyStrike() == 0) {
                        streak = "First time claiming daily, have fun!";
                    } else {
                        streak = "2+ days have passed since your last daily, so your streak got reset :(\n" +
                                "Old streak: `" + playerData.getDailyStrike() + "`";
                    }
                    playerData.setDailyStrike(1);
                }

                if(playerData.getDailyStrike() > 5) {
                    streak += "\nYou won a bonus of $150 for claiming your daily for 5 days in a row or more!";
                    money += 150;
                }

                if(playerData.getDailyStrike() > 10) {
                    playerData.addBadge(Badge.CLAIMER);
                }

                player.addMoney(money);
                playerData.setLastDailyAt(System.currentTimeMillis());
                player.save();
                event.getChannel().sendMessage(EmoteReference.CORRECT + "You got **$" + money + "** daily credits.\n\n" + streak).queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Daily command")
                        .setDescription("**Gives you $150 credits per day (or between 150 and 180 if you transfer it to another person)**.")
                        .build();
            }
        });

        cr.registerAlias("daily", "dailies");
    }

    @Subscribe
    public void gamble(CommandRegistry cr) {
        cr.register("gamble", new SimpleCommand(Category.CURRENCY) {
            final RateLimiter rateLimiter = new RateLimiter(TimeUnit.SECONDS, 18, true);
            SecureRandom r = new SecureRandom();

            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Player player = MantaroData.db().getPlayer(event.getMember());

                if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event)) return;

                if(player.getMoney() <= 0) {
                    event.getChannel().sendMessage(EmoteReference.ERROR2 + "You're broke. Search for some credits first!").queue();
                    return;
                }

                if(player.getMoney() > (long) (Integer.MAX_VALUE) * 4) {
                    event.getChannel().sendMessage(EmoteReference.ERROR2 + "You have too much money! Maybe transfer or buy items? Now you can also use `~>slots` for all your gambling needs! " +
                            "Thanks for not breaking the local bank.").queue();
                    return;
                }

                double multiplier;
                long i;
                int luck;
                try {
                    switch(content) {
                        case "all":
                        case "everything":
                            i = player.getMoney();
                            multiplier = 1.4d + (r.nextInt(1500) / 1000d);
                            luck = 30 + (int) (multiplier * 15) + r.nextInt(20);
                            break;
                        case "half":
                            i = player.getMoney() == 1 ? 1 : player.getMoney() / 2;
                            multiplier = 1.2d + (r.nextInt(1500) / 1000d);
                            luck = 20 + (int) (multiplier * 13) + r.nextInt(20);
                            break;
                        case "quarter":
                            i = player.getMoney() == 1 ? 1 : player.getMoney() / 4;
                            multiplier = 1.1d + (r.nextInt(1100) / 1000d);
                            luck = 25 + (int) (multiplier * 10) + r.nextInt(18);
                            break;
                        default:
                            i = Long.parseLong(content);
                            if(i > player.getMoney() || i < 0) throw new UnsupportedOperationException();
                            multiplier = 1.1d + (i / player.getMoney() * r.nextInt(1300) / 1000d);
                            luck = 15 + (int) (multiplier * 15) + r.nextInt(10);
                            break;
                    }
                } catch(NumberFormatException e) {
                    event.getChannel().sendMessage(EmoteReference.ERROR2 + "Please type a valid number less than or equal to your current balance or" +
                            " `all` to gamble all your credits.").queue();
                    return;
                } catch(UnsupportedOperationException e) {
                    event.getChannel().sendMessage(EmoteReference.ERROR2 + "Please type a value within your balance.").queue();
                    return;
                }

                User user = event.getAuthor();
                long gains = (long) (i * multiplier);
                gains = Math.round(gains * 0.55);

                final int finalLuck = luck;
                final long finalGains = gains;

                if(i >= Integer.MAX_VALUE / 4) {
                    player.setLocked(true);
                    player.save();
                    event.getChannel().sendMessage(EmoteReference.WARNING + "You're about to bet **" + i + "** " +
                            "credits (which seems to be a lot). Are you sure? Type **yes** to continue and **no** otherwise.").queue();
                    InteractiveOperations.create(event.getChannel(), 30, new InteractiveOperation() {
                        @Override
                        public int run(GuildMessageReceivedEvent e) {
                            if(e.getAuthor().getId().equals(user.getId())) {
                                if(e.getMessage().getContent().equalsIgnoreCase("yes")) {
                                    proceedGamble(event, player, finalLuck, random, i, finalGains);
                                    return COMPLETED;
                                } else if(e.getMessage().getContent().equalsIgnoreCase("no")) {
                                    e.getChannel().sendMessage(EmoteReference.ZAP + "Cancelled bet.").queue();
                                    player.setLocked(false);
                                    player.saveAsync();
                                    return COMPLETED;
                                }
                            }

                            return IGNORED;
                        }

                        @Override
                        public void onExpire() {
                            event.getChannel().sendMessage(EmoteReference.ERROR + "Time to complete the operation has ran out.")
                                    .queue();
                            player.setLocked(false);
                            player.saveAsync();
                        }
                    });

                    return;
                }

                proceedGamble(event, player, luck, random, i, gains);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Gamble command")
                        .setDescription("Gambles your money")
                        .addField("Usage", "~>gamble <all/half/quarter> or ~>gamble <amount>", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void loot(CommandRegistry cr) {
        cr.register("loot", new SimpleCommand(Category.CURRENCY) {
            final RateLimiter rateLimiter = new RateLimiter(TimeUnit.MINUTES, 5, true);
            Random r = new Random();

            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Player player = MantaroData.db().getPlayer(event.getMember());

                if(player.isLocked()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot loot now.").queue();
                    return;
                }

                if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event)) return;

                TextChannelGround ground = TextChannelGround.of(event);

                if(r.nextInt(125) == 0) { //1 in 125 chance of it dropping a loot crate.
                    ground.dropItem(Items.LOOT_CRATE);
                    if(player.getData().addBadge(Badge.LUCKY)) player.saveAsync();
                }

                List<ItemStack> loot = ground.collectItems();
                int moneyFound = ground.collectMoney() + Math.max(0, r.nextInt(50) - 10);


                if(MantaroData.db().getUser(event.getMember()).isPremium() && moneyFound > 0) {
                    moneyFound = moneyFound + random.nextInt(moneyFound);
                }

                if(!loot.isEmpty()) {
                    String s = ItemStack.toString(ItemStack.reduce(loot));
                    String overflow;
                    if(player.getInventory().merge(loot)) {
                        overflow = "But you already had too many items, so you decided to throw away the excess. ";
                    } else {
                        overflow = "";
                    }
                    if(moneyFound != 0) {
                        if(player.addMoney(moneyFound)) {
                            event.getChannel().sendMessage(EmoteReference.POPPER + "Digging through messages, you found " + s + ", along " +
                                    "with $" + moneyFound + " credits! " + overflow).queue();
                        } else {
                            event.getChannel().sendMessage(EmoteReference.POPPER + "Digging through messages, you found " + s + ", along " +
                                    "with $" + moneyFound + " credits. " + overflow + "But you already had too many credits. Your bag overflowed" +
                                    ".\nCongratulations, you exploded a Java long. Here's a buggy money bag for you.").queue();
                        }
                    } else {
                        event.getChannel().sendMessage(EmoteReference.MEGA + "Digging through messages, you found " + s + ". " + overflow).queue();
                    }
                } else {
                    if(moneyFound != 0) {
                        if(player.addMoney(moneyFound)) {
                            event.getChannel().sendMessage(EmoteReference.POPPER + "Digging through messages, you found **$" + moneyFound +
                                    " credits!**").queue();
                        } else {
                            event.getChannel().sendMessage(EmoteReference.POPPER + "Digging through messages, you found $" + moneyFound +
                                    " credits. But you already had too many credits. Your bag overflowed.\nCongratulations, you exploded " +
                                    "a Java long. Here's a buggy money bag for you.").queue();
                        }
                    } else {
                        String msg = "Digging through messages, you found nothing but dust";

                        if(r.nextInt(100) > 85){
                            msg += "\n" +
                                    "Seems like you've got so much dust here... You might want to clean this up before it gets too messy!";
                        }
                        event.getChannel().sendMessage(EmoteReference.SAD + msg).queue();
                    }
                }
                player.saveAsync();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Loot command")
                        .setDescription("**Loot the current chat for items, for usage in Mantaro's currency system.**\n"
                                + "Currently, there are ``" + Items.ALL.length + "`` items available in chance," +
                                "in which you have a `random chance` of getting one or more.")
                        .addField("Usage", "~>loot", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void balance(CommandRegistry cr) {
        cr.register("balance", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                User user = event.getAuthor();
                boolean isExternal = false;
                List<Member> found = FinderUtil.findMembers(content, event.getGuild());
                if(found.isEmpty() && !content.isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "Your search yielded no results :(").queue();
                    return;
                }

                if(found.size() > 1 && !content.isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.THINKING + "Too many users found, maybe refine your search? (ex. use name#discriminator)\n" +
                            "**Users found:** " + found.stream().map(m -> m.getUser().getName() + "#" + m.getUser().getDiscriminator()).collect(Collectors.joining(", "))).queue();
                    return;
                }

                if(found.size() == 1) {
                    user = found.get(0).getUser();
                    isExternal = true;
                }

                long balance = MantaroData.db().getPlayer(user).getMoney();

                event.getChannel().sendMessage(EmoteReference.DIAMOND + (isExternal ? user.getName() + "'s balance is: **$" : "Your balance is: **$") + balance + "**").queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return baseEmbed(event, "Balance command")
                        .setDescription("**Shows your current balance or another person's balance.**")
                        .build();
            }
        });
    }

    @Subscribe
    public void richest(CommandRegistry cr) {
        cr.register("leaderboard", new SimpleCommand(Category.CURRENCY) {
            final RateLimiter rateLimiter = new RateLimiter(TimeUnit.SECONDS, 10);

            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {

                if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event)) return;

                Map<String, Optional<String>> opts = StringUtils.parse(args);
                List<String> memberIds = null;
                boolean local = false;
                /*if(opts.containsKey("local")){
                     memberIds = event.getGuild().getMembers().stream().map(m -> m.getUser().getId() + ":g").collect(Collectors.toList());
                     local = true;
                }*/


                //Value used in lambda expresion must be blablabla fuck it.
                boolean isLocal = local;
                List<String> mids = memberIds;
                String pattern = ":g$";

                OrderBy template =
                        r.table("players")
                                .orderBy()
                                .optArg("index", r.desc("money"));

                if(args.length > 0 && (args[0].equalsIgnoreCase("lvl") || args[0].equalsIgnoreCase("level"))) {
                    Cursor<Map> m = r.table("players")
                            .orderBy()
                            .optArg("index", r.desc("level"))
                            .filter(player -> isLocal ? r.expr(mids).contains(player.g("id")) : player.g("id").match(pattern))
                            .map(player -> player.pluck("id", "level"))
                            .limit(15)
                            .run(MantaroData.conn(), OptArgs.of("read_mode", "outdated"));
                    AtomicInteger i = new AtomicInteger();
                    List<Map> c = m.toList();
                    m.close();

                    event.getChannel().sendMessage(
                            baseEmbed(event,
                                    "Level leaderboard" + (isLocal ? " for server " + event.getGuild().getName() : ""),
                                    event.getJDA().getSelfUser().getEffectiveAvatarUrl()
                            ).setDescription(c.stream()
                                    .map(map -> Pair.of(MantaroBot.getInstance().getUserById(map.get("id").toString().split(":")[0]), map.get("level").toString()))
                                    .filter(p -> Objects.nonNull(p.getKey()))
                                    .map(p -> String.format("%d - **%s#%s** - Level: %s", i.incrementAndGet(), p.getKey().getName(), p
                                            .getKey().getDiscriminator(), p.getValue()))
                                    .collect(Collectors.joining("\n"))
                            ).setThumbnail("https://maxcdn.icons8.com/office/PNG/512/Sports/trophy-512.png").build()
                    ).queue();


                    return;
                }


                if(args.length > 0 && (args[0].equalsIgnoreCase("rep") || args[0].equalsIgnoreCase("reputation"))) {
                    Cursor<Map> m = r.table("players")
                            .orderBy()
                            .optArg("index", r.desc("reputation"))
                            .filter(player -> isLocal ? r.expr(mids).contains(player.g("id")) : player.g("id").match(pattern))
                            .map(player -> player.pluck("id", "reputation"))
                            .limit(15)
                            .run(MantaroData.conn(), OptArgs.of("read_mode", "outdated"));
                    AtomicInteger i = new AtomicInteger();
                    List<Map> c = m.toList();
                    m.close();

                    event.getChannel().sendMessage(
                            baseEmbed(event,
                                    "Reputation leaderboard" + (isLocal ? " for server " + event.getGuild().getName() : ""),
                                    event.getJDA().getSelfUser().getEffectiveAvatarUrl()
                            ).setDescription(c.stream()
                                    .map(map -> Pair.of(MantaroBot.getInstance().getUserById(map.get("id").toString().split(":")[0]), map.get("reputation").toString()))
                                    .filter(p -> Objects.nonNull(p.getKey()))
                                    .map(p -> String.format("%d - **%s#%s** - Reputation: %s", i.incrementAndGet(), p.getKey().getName(), p
                                            .getKey().getDiscriminator(), p.getValue()))
                                    .collect(Collectors.joining("\n"))
                            ).setThumbnail("https://maxcdn.icons8.com/office/PNG/512/Sports/trophy-512.png").build()
                    ).queue();


                    return;
                }

                Cursor<Map> c1 = getGlobalRichest(template, pattern, mids, isLocal);
                AtomicInteger i = new AtomicInteger();
                List<Map> c = c1.toList();
                c1.close();

                event.getChannel().sendMessage(
                        baseEmbed(event,
                                "Money leaderboard" + (isLocal ? " for server " + event.getGuild().getName() : ""),
                                event.getJDA().getSelfUser().getEffectiveAvatarUrl()
                        ).setDescription(c.stream()
                                .map(map -> Pair.of(MantaroBot.getInstance().getUserById(map.get("id").toString().split(":")[0]), map.get("money").toString()))
                                .filter(p -> Objects.nonNull(p.getKey()))
                                .map(p -> String.format("%d - **%s#%s** - Credits: $%s", i.incrementAndGet(), p.getKey().getName(), p
                                        .getKey().getDiscriminator(), p.getValue()))
                                .collect(Collectors.joining("\n"))
                        ).setThumbnail("https://maxcdn.icons8.com/office/PNG/512/Sports/trophy-512.png").build()
                ).queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Leaderboard")
                        .setDescription("**Returns the leaderboard.**")
                        .addField("Usage", "`~>leaderboard` - **Returns the money leaderboard.**\n" +
                                "`~>leaderboard rep` - **Returns the reputation leaderboard.**\n" +
                                "`~>leaderboard lvl` - **Returns the level leaderboard.**", false)
                        .build();
            }
        });

        cr.registerAlias("leaderboard", "richest");
    }

    @Subscribe
    public void slots(CommandRegistry cr) {
        RateLimiter rateLimiter = new RateLimiter(TimeUnit.SECONDS, 25);
        String[] emotes = {"\uD83C\uDF52", "\uD83D\uDCB0", "\uD83D\uDCB2", "\uD83E\uDD55", "\uD83C\uDF7F", "\uD83C\uDF75", "\uD83C\uDFB6"};
        Random random = new SecureRandom();
        List<String> winCombinations = new ArrayList<>();

        for(String emote : emotes) {
            winCombinations.add(emote + emote + emote);
        }

        cr.register("slots", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Map<String, Optional<String>> opts = StringUtils.parse(args);
                long money = 50;
                int slotsChance = 23; //23% raw chance of winning, completely random chance of winning on the other random iteration
                boolean isWin = false;
                boolean coinSelect = false;

                if(opts.containsKey("useticket")) {
                    coinSelect = true;
                }

                if(args.length == 1 && !coinSelect) {
                    try {
                        money = Math.abs(Integer.parseInt(args[0]));

                        if(money < 25) {
                            event.getChannel().sendMessage(EmoteReference.ERROR + "The minimum amount is 25!").queue();
                            return;
                        }

                        if(money > SLOTS_MAX_MONEY) {
                            event.getChannel().sendMessage(EmoteReference.WARNING + "This machine cannot dispense that much money!").queue();
                            return;
                        }
                    } catch(NumberFormatException e) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "That's not a number!").queue();
                        return;
                    }
                }

                Player player = MantaroData.db().getPlayer(event.getAuthor());

                if(player.getMoney() < money && !coinSelect) {
                    event.getChannel().sendMessage(EmoteReference.SAD + "You don't have enough money to play the slots machine!").queue();
                    return;
                }

                if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event)) return;

                if(coinSelect) {
                    if(player.getInventory().containsItem(Items.SLOT_COIN)) {
                        player.getInventory().process(new ItemStack(Items.SLOT_COIN, -1));
                        player.saveAsync();
                        slotsChance = slotsChance + 10;
                    } else {
                        event.getChannel().sendMessage(EmoteReference.SAD + "You wanted to use tickets but you don't have any :<").queue();
                        return;
                    }
                } else {
                    player.removeMoney(money);
                    player.saveAsync();
                }


                StringBuilder message = new StringBuilder(String.format("%s**You used %s and rolled the slot machine!**\n\n", EmoteReference.DICE, coinSelect ? "a slot ticket" : money + " credits"));
                StringBuilder builder = new StringBuilder();
                for(int i = 0; i < 9; i++) {
                    if(i > 1 && i % 3 == 0) {
                        builder.append("\n");
                    }
                    builder.append(emotes[random.nextInt(emotes.length)]);
                }

                String toSend = builder.toString();
                int gains = 0;
                String[] rows = toSend.split("\\r?\\n");

                if(random.nextInt(100) < slotsChance) {
                    rows[1] = winCombinations.get(random.nextInt(winCombinations.size()));
                }

                if(winCombinations.contains(rows[1])) {
                    isWin = true;
                    gains = random.nextInt((int) Math.round(money * 1.76)) + 14;
                }

                rows[1] = rows[1] + " \u2b05";
                toSend = String.join("\n", rows);

                if(isWin) {
                    message.append(toSend).append("\n\n").append(String.format("And you won **%d** credits! Lucky! ", gains)).append(EmoteReference.POPPER);
                    player.addMoney(gains);
                    player.saveAsync();
                } else {
                    message.append(toSend).append("\n\n").append("And you lost ").append(EmoteReference.SAD).append("\n").append("I hope you do better next time!");
                }

                message.append("\n");
                event.getChannel().sendMessage(message.toString()).queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Slots Command")
                        .setDescription("**Rolls the slot machine. Requires a default of 50 coins to roll.**")
                        .addField("Considerations", "You can gain a maximum of put credits * 1.76 coins from it.\n" +
                                "You can use the `-useticket` argument to use a slot ticket (slightly bigger chance)", false)
                        .addField("Usage", "`~>slots` - Default one, 50 coins.\n" +
                                "`~>slots <credits>` - Puts x credits on the slot machine. Max of " + SLOTS_MAX_MONEY + " coins.\n" +
                                "`~>slots -useticket` - Rolls the slot machine with one slot coin.", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void mine(CommandRegistry cr) {
        cr.register("mine", new SimpleCommand(Category.CURRENCY) {
            final RateLimiter rateLimiter = new RateLimiter(TimeUnit.MINUTES, 6, false);
            final Random r = new Random();

            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                User user = event.getAuthor();
                Player player = MantaroData.db().getPlayer(user);

                if(!player.getInventory().containsItem(Items.BROM_PICKAXE)) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You don't have any pickaxe to mine!").queue();
                    return;
                }

                if(!handleDefaultRatelimit(rateLimiter, user, event)) return;

                if(r.nextInt(100) > 75) { //35% chance of it breaking the pick.
                    event.getChannel().sendMessage(EmoteReference.SAD + "One of your picks broke while mining.").queue();
                    player.getInventory().process(new ItemStack(Items.BROM_PICKAXE, -1));
                    player.saveAsync();
                    return;
                }

                long money = Math.max(50, r.nextInt(550)); //50 to 550 credits.
                String message = EmoteReference.PICK + "You mined minerals worth $" + money + " credits!";

                if(r.nextInt(400) > 350) {
                    if(player.getInventory().getAmount(Items.DIAMOND) == 5000) {
                        message += "\nHuh, you found a diamond while mining, but you already had too much, so we sold it for you!";
                        money += Items.DIAMOND.getValue() * 0.9;
                    } else {
                        player.getInventory().process(new ItemStack(Items.DIAMOND, 1));
                        message += "\nHoh! You got lucky and found a diamond while mining, check your inventory!";
                    }
                }

                event.getChannel().sendMessage(message).queue();
                player.addMoney(money);
                player.saveAsync();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Mine command")
                        .setDescription("**Mines minerals to gain some credits. More lucrative than loot, but needs pickaxes.")
                        .build();
            }
        });
    }

    private void proceedGamble(GuildMessageReceivedEvent event, Player player, int luck, Random r, long i, long gains) {
        if(luck > r.nextInt(140)) {
            if(player.addMoney(gains)) {
                if(gains > Integer.MAX_VALUE) {
                    if(!player.getData().hasBadge(Badge.GAMBLER)) {
                        player.getData().addBadge(Badge.GAMBLER);
                        player.saveAsync();
                    }
                }
                event.getChannel().sendMessage(EmoteReference.DICE + "Congrats, you won " + gains + " credits and got to keep what you " +
                        "had!").queue();
            } else {
                event.getChannel().sendMessage(EmoteReference.DICE + "Congrats, you won " + gains + " credits. But you already had too " +
                        "many credits. Your bag overflowed.\nCongratulations, you exploded a Java long. Here's a buggy money bag for you" +
                        ".").queue();
            }
        } else {
            player.setMoney(Math.max(0, player.getMoney() - i));
            event.getChannel().sendMessage("\uD83C\uDFB2 Sadly, you lost " + (player.getMoney() == 0 ? "all your" : i) + " credits! " +
                    "\uD83D\uDE26").queue();
        }
        player.setLocked(false);
        player.saveAsync();
    }

    private Cursor<Map> getGlobalRichest(OrderBy template, String pattern, List<String> mids, boolean isLocal) {
        return template.filter(player -> player.g("id").match(pattern))
                .map(player -> player.pluck("id", "money"))
                .filter(player -> isLocal ? r.expr(mids).contains(player.g("id")) : true)
                .limit(15)
                .run(MantaroData.conn(), OptArgs.of("read_mode", "outdated"));
    }
}
