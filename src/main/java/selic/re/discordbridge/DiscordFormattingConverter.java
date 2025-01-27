package selic.re.discordbridge;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildChannel;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.Timestamp;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.*;

import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordFormattingConverter {
    // Order matters here, we don't want _ to trigger and stop __ from being detected
    private static final Formatting[] FORMATTING_PRIORITIES = {
        // Three character triggers
//        Formatting.BlockQuotes,
//        Formatting.CodeBlock,

        // Two character triggers
        Formatting.Bold,
        Formatting.Underline,
        Formatting.Strikethrough,
        Formatting.Spoilers,

        // One character triggers
        Formatting.Italic,
        Formatting.InlineCode,
//        Formatting.Quote,
    };

    /**
     * Converts a given channel type constant name to a readable format, e.g 'VOICE' -> 'Voice channel'
     */
    private static final Function<String, String> CHANNEL_TYPE_STRINGIFIER =
        CaseFormat.UPPER_UNDERSCORE.converterTo(CaseFormat.UPPER_CAMEL).andThen("%s channel"::formatted);

    /**
     * A single-group implementation of {@link TimeFormat#MARKDOWN} that also accounts for trailing characters
     */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(<t:-?\\d{1,17}(?::[tTdDfFR])?>).*?");
    private static final Pattern USER_MENTION_PATTERN = Pattern.compile("^<@!?(\\d+)>");

    /**
     * String formatter templates for each timestamp formatting style
     *
     * Relative timestamps aren't effective for in-game messages due to the static nature
     * of the chat history, so we use a short date-times format (the default style).
     */
    private static final Map<TimeFormat, String> TIMESTAMP_FORMATS =
        Maps.immutableEnumMap(ImmutableMap.<TimeFormat, String>builder()
            .put(TimeFormat.TIME_SHORT, "%1$tH:%1$tM UTC")
            .put(TimeFormat.TIME_LONG, "%1$tH:%1$tM:%1$tS UTC")
            .put(TimeFormat.DATE_SHORT, "%1$te/%1$tm/%1$tY")
            .put(TimeFormat.DATE_LONG, "%1$te %1$tB %1$tY")
            .put(TimeFormat.RELATIVE, "%1$te %1$tB %1$tY %1$tH:%1$tM UTC")
            .put(TimeFormat.DATE_TIME_SHORT, "%1$te %1$tB %1$tY %1$tH:%1$tM UTC")
            .put(TimeFormat.DATE_TIME_LONG, "%1$tA, %1$te %1$tB %1$tY %1$tH:%1$tM UTC")
            .build());

    private final Message message;
    private final String markdown;
    private int cursor;
    private final MutableText root = Text.empty();
    private List<ActiveFormatting> formattingStack = new ArrayList<>();
    private Set<Formatting> activeFormatting = EnumSet.noneOf(Formatting.class);
    final StringBuilder textBuffer = new StringBuilder();

    protected DiscordFormattingConverter(Message message) {
        this.message = message;
        this.markdown = message.getContentRaw();
        this.cursor = 0;
        this.formattingStack.add(new ActiveFormatting(0, "", Formatting.Root));
    }

    protected char read() {
        return markdown.charAt(cursor++);
    }

    protected boolean isEOF() {
        return cursor >= markdown.length();
    }

    protected boolean consume(String input) {
        if (cursor + input.length() <= markdown.length() && markdown.startsWith(input, cursor)) {
            cursor += input.length();
            return true;
        } else {
            return false;
        }
    }

    /**
     * An implementation of {@link #consume(String)} that matches a given regex pattern
     * against the start of the remaining markdown, returning the first captured group
     * and offsetting the cursor by its total length
     *
     * @param pattern The pattern to match against
     * @return The first captured group or null if absent
     */
    @Nullable
    protected String consumeFirst(Pattern pattern) {
        String input = markdown.substring(cursor);
        if (input.isEmpty()) { // Nothing to match
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            String first = matcher.group(1);
            cursor += first.length();
            return first;
        }
        return null;
    }

    protected boolean tryMarkdown() {
        for (Formatting formattingPriority : FORMATTING_PRIORITIES) {
            if (tryFormatting(formattingPriority)) {
                return true;
            }
        }
        return false;
    }

    protected boolean tryFormatting(Formatting formatting) {
        if (activeFormatting.contains(formatting)) {
            ActiveFormatting last = formattingStack.get(formattingStack.size() - 1);
            if (last.formatting == formatting) {
                if (this.consume(last.trigger)) {
                    popFormatting(last, false);

                    return true;
                }
            }

            // We already have this formatting but the user didn't intend to pop it - so ignore it.
            return false;
        }

        for (int i = 0; i < formatting.triggers.length; i++) {
            String trigger = formatting.triggers[i];
            if (this.consume(trigger)) {
                if (!textBuffer.isEmpty()) {
                    popSimpleText();
                }
                this.activeFormatting.add(formatting);
                this.formattingStack.add(new ActiveFormatting(textBuffer.length(), trigger, formatting));
                return true;
            }
        }
        return false;
    }

    private void popSimpleText() {
        Text text = Text.literal(textBuffer.toString());
        textBuffer.setLength(0);
        addText(text);
    }

    private void popFormatting(ActiveFormatting entry, boolean danglingToken) {
        formattingStack.remove(formattingStack.size() - 1);
        activeFormatting.remove(entry.formatting);
        if (danglingToken) {
            textBuffer.insert(entry.offset, entry.trigger);
        }
        MutableText text = Text.literal(textBuffer.toString());
        textBuffer.setLength(0);
        if (!danglingToken) {
            text.setStyle(entry.formatting.getStyle(text));
        }
        for (Text child : entry.children) {
            text.append(child);
        }
        addText(text);
    }

    protected void readToEnd() {
        while (!isEOF()) {
            if (tryMarkdown()) continue;
            if (tryMentions()) continue;
            textBuffer.append(read());
        }
        popSimpleText();

        while (!formattingStack.isEmpty()) {
            ActiveFormatting last = formattingStack.get(formattingStack.size() - 1);
            popFormatting(last, true);
        }
    }

    private boolean tryMentions() {
        Matcher userMatcher = USER_MENTION_PATTERN.matcher(markdown.substring(cursor));
        if (userMatcher.find()) {
            Member member = message.getGuild().getMemberById(userMatcher.group(1));
            if (member != null) {
                addUserMention(member.getUser());
                cursor += userMatcher.end();
                return true;
            }
        }
        for (Emote emote : message.getEmotes()) {
            if (consume(emote.getAsMention())) {
                addEmoteMention(emote);
                return true;
            }
        }
        for (TextChannel channel : message.getMentionedChannels()) {
            if (consume(channel.getAsMention())) {
                addChannelMention(channel);
                return true;
            }
        }
        for (Role role : message.getMentionedRoles()) {
            if (consume(role.getAsMention())) {
                addRoleMention(role);
                return true;
            }
        }
        String result = consumeFirst(TIMESTAMP_PATTERN);
        if (result != null) {
            addTimestamp(TimeFormat.parse(result));
            return true;
        }
        return false;
    }

    private void addRoleMention(Role role) {
        MutableText text = Text.literal("@" + role.getName());
        text.setStyle(Style.EMPTY.withColor(role.getColorRaw()).withInsertion(role.getAsMention()));
        popSimpleText();
        addText(text);
    }

    private void addChannelMention(TextChannel channel) {
        popSimpleText();
        addText(discordChannelToMinecraft(channel));
    }

    private void addEmoteMention(Emote emote) {
        popSimpleText();
        addText(discordEmoteToMinecraft(emote));
    }

    private void addUserMention(User user) {
        popSimpleText();
        addText(discordUserToMinecraft(user, message.getGuild(), true));
    }

    private void addTimestamp(Timestamp timestamp) {
        popSimpleText();
        addText(discordTimestampToMinecraft(timestamp));
    }

    private void addText(Text text) {
        if (formattingStack.size() > 0) {
            formattingStack.get(formattingStack.size() - 1).children.add(text);
        } else {
            root.append(text);
        }
    }

    public static Text discordChannelToMinecraft(GuildChannel channel) {
        String name = (channel.getType().isMessage() ? "#" : "") + channel.getName();
        String type = CHANNEL_TYPE_STRINGIFIER.apply(channel.getType().name());

        return Text.literal(name).setStyle(Style.EMPTY.withInsertion(channel.getAsMention())
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(type))));
    }

    public static Text discordMessageToMinecraft(Message message) {
        DiscordFormattingConverter converter = new DiscordFormattingConverter(message);
        converter.readToEnd();
        return converter.root;
    }

    public static MutableText discordEmoteToMinecraft(Emote emote) {
        MutableText text = Text.literal(":" + emote.getName() + ":");
        ClickEvent click = new ClickEvent(ClickEvent.Action.OPEN_URL, emote.getImageUrl());
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(emote.getGuild() == null ? "Emote" : "Emote from " + emote.getGuild().getName()));
        text.setStyle(Style.EMPTY.withClickEvent(click).withHoverEvent(hover).withInsertion(":" + emote.getName() + ":"));
        return text;
    }

    public static Text discordUserToMinecraft(User user, Guild guild, boolean asMention) {
        @Nullable Member member = guild.getMember(user);
        MutableText tooltip = Text.literal(user.getAsTag());
        String userName = user.getName();
        Style style = Style.EMPTY;
        boolean online = false;

        ServerPlayerEntity player = DiscordBot.instance().getPlayer(user);
        if (player != null) {
            style = style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell " + player.getGameProfile().getName() + " "));
            online = true;
        }

        if (online) {
            tooltip.append("\nIn-game");
        } else {
            tooltip.append("\nOn Discord");
        }

        if (member != null) {
            userName = member.getEffectiveName();
            style = style.withColor(member.getColorRaw());

            List<Role> roles = Lists.newArrayList(member.getRoles());

            // Roles are ordered higher value for higher role positioning, so we need to reverse the default
            // comparison order.This is effectively 'comparing(Role::getPosition).reversed()' without boxing
            roles.sort(Comparator.comparingInt(role -> -role.getPosition()));

            for (Role role : roles) {
                tooltip.append("\n- ");
                tooltip.append(Text.literal(role.getName())
                    .setStyle(Style.EMPTY.withColor(role.getColorRaw())));
            }
        }

        if (asMention) {
            userName = "@" + userName;
        }

        return Text.literal(userName).setStyle(style.withInsertion("@" + user.getAsTag())
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip)));
    }

    /**
     * Parses the given timestamp markdown into a chat component, with a tooltip
     * containing the full UTC date and time regardless of the timestamp style,
     * and an insertion for the original markdown string.
     *
     * @param timestamp The timestamp instance
     * @return A chat component representing the parsed timestamp
     * @see <a href="https://discord.com/developers/docs/reference#message-formatting">
     *     discord.com/developers/docs/reference#message-formatting
     *     </a>
     */
    public static Text discordTimestampToMinecraft(Timestamp timestamp) {
        // TODO Allow configuration of preferred time zone and hour (24/12) format?
        LocalDateTime dateTime = LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
        return Text.literal("[")
            .append(TIMESTAMP_FORMATS.get(timestamp.getFormat()).formatted(dateTime))
            .append("]")
            .setStyle(Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Text.literal(TIMESTAMP_FORMATS.get(TimeFormat.DATE_TIME_LONG).formatted(dateTime))
            )).withInsertion(timestamp.toString()));
    }

    public static String minecraftToDiscord(Text root) {
        TextToMarkdownVisitor visitor = new TextToMarkdownVisitor();
        root.visit(visitor, Style.EMPTY);
        return visitor.finish();
    }

    protected enum Formatting {
        Root((msg, style) -> style),
        Bold((msg, style) -> style.withBold(true), "**"),
        Italic((msg, style) -> style.withItalic(true), "*", "_"),
        Underline((msg, style) -> style.withUnderline(true), "__"),
        Strikethrough((msg, style) -> style.withStrikethrough(true), "~~"),
        InlineCode((msg, style) -> style.withColor(net.minecraft.util.Formatting.GRAY), "`"),
        //CodeBlock(style -> style, "```"),
        //Quote(style -> style, ">"),
        //BlockQuotes(style -> style, ">>>"),
        Spoilers((msg, style) -> style.withObfuscated(true).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, msg)), "||"),
        ;

        protected final BiFunction<Text, Style, Style> styler;
        protected final String[] triggers;

        Formatting(BiFunction<Text, Style, Style> styler, String... triggers) {
            this.styler = styler;
            this.triggers = triggers;
        }

        protected Style getStyle(Text message) {
            return styler.apply(message, Style.EMPTY);
        }
    }

    protected static class ActiveFormatting {
        protected final int offset;
        protected final String trigger;
        protected final Formatting formatting;
        protected final List<Text> children = new ArrayList<>();

        public ActiveFormatting(int offset, String trigger, Formatting formatting) {
            this.offset = offset;
            this.trigger = trigger;
            this.formatting = formatting;
        }
    }
}
