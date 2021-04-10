package com.sx4.bot.handlers;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.sx4.bot.core.Sx4;
import com.sx4.bot.database.Database;
import com.sx4.bot.database.model.Operators;
import com.sx4.bot.entities.management.WhitelistType;
import com.sx4.bot.entities.mod.Reason;
import com.sx4.bot.entities.mod.action.Action;
import com.sx4.bot.entities.mod.auto.MatchAction;
import com.sx4.bot.entities.mod.auto.RegexType;
import com.sx4.bot.entities.settings.HolderType;
import com.sx4.bot.exceptions.mod.ModException;
import com.sx4.bot.formatter.StringFormatter;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.FutureUtility;
import com.sx4.bot.utility.ModUtility;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AntiRegexHandler implements EventListener {

    public static final String INVITE_REGEX = "discord(?:(?:(?:app)?\\.com|\\.co|\\.media)/invite|\\.gg)/([a-z\\-0-9]{2,32})";

    private final Pattern invitePattern = Pattern.compile(AntiRegexHandler.INVITE_REGEX, Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Sx4 bot;

    public AntiRegexHandler(Sx4 bot) {
        this.bot = bot;
    }

    private String format(String message, User user, TextChannel channel, ObjectId id, int currentAttempts, int maxAttempts, Action action) {
        return new StringFormatter(message)
            .user(user)
            .channel(channel)
            .append("regex.id", id.toHexString())
            .append("regex.action.name", action == null ? null : action.getName().toLowerCase())
            .append("regex.action.exists", action != null)
            .append("regex.attempts.current", currentAttempts)
            .append("regex.attempts.max", maxAttempts)
            .parse();
    }

    public void handle(Message message) {
        Member member = message.getMember();
        if (member == null || member.hasPermission(Permission.ADMINISTRATOR)) {
            return;
        }

        User user = member.getUser();
        Guild guild = message.getGuild();
        Member selfMember = guild.getSelfMember();
        TextChannel textChannel = message.getTextChannel();

        if (user.isBot()) {
            return;
        }

        this.executor.submit(() -> {
            long guildId = guild.getIdLong(), userId = member.getIdLong(), channelId = textChannel.getIdLong();

            Category parent = textChannel.getParent();
            List<Role> roles = member.getRoles();

            List<Document> regexes = this.bot.getDatabase().getRegexes(Filters.eq("guildId", guildId), Database.EMPTY_DOCUMENT).into(new ArrayList<>());

            String content = message.getContentRaw();

            List<CompletableFuture<Document>> matches = new ArrayList<>();
            Regexes : for (Document regex : regexes) {
                if (!regex.get("enabled", true)) {
                    continue;
                }

                List<Document> channels = regex.getList("whitelist", Document.class, Collections.emptyList());

                Document channel = channels.stream()
                    .filter(d -> (d.getInteger("type") == WhitelistType.CHANNEL.getId() && d.getLong("id") == channelId) || (d.getInteger("type") == WhitelistType.CATEGORY.getId() && parent != null && d.getLong("id") == parent.getIdLong()))
                    .min(Comparator.comparingInt(d -> d.getInteger("type", 0)))
                    .orElse(Database.EMPTY_DOCUMENT);

                List<Document> holders = channel.getList("holders", Document.class, Collections.emptyList());
                for (Document holder : holders) {
                    long holderId = holder.getLong("id");
                    int type = holder.getInteger("type");
                    if (type == HolderType.USER.getType() && userId == holderId) {
                        continue Regexes;
                    } else if (type == HolderType.ROLE.getType() && (guildId == holderId || roles.stream().anyMatch(role -> role.getIdLong() == holderId))) {
                        continue Regexes;
                    }
                }

                RegexType type = RegexType.fromId(regex.getInteger("type"));

                Pattern pattern = type == RegexType.REGEX ? Pattern.compile(regex.getString("pattern")) : this.invitePattern;

                Matcher matcher;
                try {
                    matcher = this.executor.submit(() -> pattern.matcher(content)).get(2000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | InterruptedException | ExecutionException e) {
                    continue;
                }

                Set<String> codes = new HashSet<>();
                int matchCount = 0, totalCount = 0;
                while (matcher.find()) {
                    List<Document> groups = channel.getList("groups", Document.class, Collections.emptyList());
                    for (Document group : groups) {
                        List<String> strings = group.getList("strings", String.class, Collections.emptyList());

                        String match = matcher.group(group.getInteger("group"));
                        if (match != null && strings.contains(match)) {
                            matchCount++;
                        }
                    }

                    if (type == RegexType.INVITE) {
                        codes.add(matcher.group(1));
                    }

                    totalCount++;
                }

                if (matchCount == totalCount) {
                    continue;
                }

                CompletableFuture<Document> future;
                if (type == RegexType.INVITE) {
                    List<CompletableFuture<Invite>> futures = codes.stream()
                        .map(code -> Invite.resolve(message.getJDA(), code, true).submit())
                        .collect(Collectors.toList());

                    List<Long> guilds = channel.getList("guilds", Long.class, Collections.emptyList());

                    future = FutureUtility.anyOf(futures, invite -> {
                        Invite.Guild inviteGuild = invite.getGuild();
                        return inviteGuild == null || (!guilds.contains(inviteGuild.getIdLong()) && inviteGuild.getIdLong() != guildId);
                    }).thenApply(invite -> invite == null ? null : regex);
                } else {
                    future = CompletableFuture.completedFuture(regex);
                }

                matches.add(future);
            }

            FutureUtility.anyOf(matches, Objects::nonNull).thenAccept(regex -> {
                if (regex == null) {
                    return;
                }

                ObjectId id = regex.getObjectId("_id"), regexId = regex.getObjectId("regexId");
                RegexType type = RegexType.fromId(regex.getInteger("type"));

                int currentAttempts = this.bot.getAntiRegexManager().getAttempts(id, userId);

                Document match = regex.get("match", Database.EMPTY_DOCUMENT);
                long matchAction = match.get("action", MatchAction.ALL);

                Document mod = regex.get("mod", Database.EMPTY_DOCUMENT);
                Document actionData = mod.get("action", Document.class);

                Action action = actionData == null ? null : Action.fromData(actionData);

                Document attempts = regex.get("attempts", Database.EMPTY_DOCUMENT);
                int maxAttempts = attempts.get("amount", 3);

                String matchMessage = this.format(match.get("message", type.getDefaultMatchMessage()),
                    user, textChannel, id, currentAttempts + 1, maxAttempts, action);

                String modMessage = this.format(mod.get("message", type.getDefaultModMessage()),
                    user, textChannel, id, currentAttempts + 1, maxAttempts, action);

                if ((matchAction & MatchAction.DELETE_MESSAGE.getRaw()) == MatchAction.DELETE_MESSAGE.getRaw() && selfMember.hasPermission(textChannel, Permission.MESSAGE_MANAGE)) {
                    message.delete().queue();
                }

                boolean canSend = selfMember.hasPermission(textChannel, Permission.MESSAGE_WRITE);
                boolean send = (matchAction & MatchAction.SEND_MESSAGE.getRaw()) == MatchAction.SEND_MESSAGE.getRaw() && canSend;
                if (action != null && currentAttempts + 1 >= maxAttempts) {
                    Reason reason = new Reason(String.format("Sent a message which matched regex `%s` %d time%s", id.toHexString(), maxAttempts, maxAttempts == 1 ? "" : "s"));

                    ModUtility.performAction(this.bot, action, member, selfMember, reason).thenCompose(result -> {
                        if (send) {
                            textChannel.sendMessage(modMessage).allowedMentions(EnumSet.allOf(Message.MentionType.class)).queue();
                        }

                        Bson filter = Filters.and(Filters.eq("userId", userId), Filters.eq("regexId", id));
                        return this.bot.getDatabase().deleteRegexAttempt(filter);
                    }).whenComplete((result, exception) -> {
                        if (exception instanceof ModException) {
                            textChannel.sendMessage(exception.getMessage() + " " + this.bot.getConfig().getFailureEmote()).queue();
                            return;
                        }

                        if (ExceptionUtility.sendErrorMessage(this.bot.getShardManager(), exception)) {
                            return;
                        }

                        this.bot.getAntiRegexManager().clearAttempts(id, userId);
                    });

                    return;
                }

                if (send) {
                    textChannel.sendMessage(matchMessage).allowedMentions(EnumSet.allOf(Message.MentionType.class)).queue();
                }

                Document reset = attempts.get("reset", Document.class);

                List<Bson> update = List.of(
                    Operators.set("attempts", Operators.let(new Document("attempts", Operators.ifNull("$attempts", 0)), Operators.cond(Operators.exists("$reset"), Operators.max(1, Operators.add(1, Operators.subtract("$$attempts", Operators.multiply(Operators.floor(Operators.divide(Operators.subtract(Operators.nowEpochSecond(), "$lastAttempt"), "$reset.after")), "$reset.amount")))), Operators.add("$$attempts", 1)))),
                    Operators.set("lastAttempt", Operators.nowEpochSecond()),
                    reset == null ? Operators.unset("reset") : Operators.set("reset", reset)
                );

                Bson filter = Filters.and(Filters.eq("userId", userId), Filters.eq("regexId", id));
                FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().upsert(true).projection(Projections.include("attempts")).returnDocument(ReturnDocument.AFTER);

                this.bot.getDatabase().findAndUpdateRegexAttempt(filter, update, options).whenComplete((data, exception) -> {
                    if (ExceptionUtility.sendErrorMessage(this.bot.getShardManager(), exception)) {
                        return;
                    }

                    this.bot.getAntiRegexManager().setAttempts(id, userId, data.getInteger("attempts", 0));
                });
            });
        });
    }

    @Override
    public void onEvent(GenericEvent event) {
        if (event instanceof GuildMessageReceivedEvent) {
            this.handle(((GuildMessageReceivedEvent) event).getMessage());
        } else if (event instanceof GuildMessageUpdateEvent) {
            this.handle(((GuildMessageUpdateEvent) event).getMessage());
        }
    }

}


