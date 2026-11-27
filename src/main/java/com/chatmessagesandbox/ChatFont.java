package com.chatmessagesandbox;

import net.runelite.api.FontID;

/**
 * Game fonts that can be used for a sandbox message.
 *
 * <p>The chatbox draws text with the game's own bitmap fonts, which only exist in a few fixed sizes.
 * Each entry maps Small / Medium / Large onto the closest sizes that font has; fonts with only one
 * size use it for all three.
 */
enum ChatFont
{
    RUNESCAPE("RuneScape (default)", FontID.PLAIN_11, FontID.PLAIN_12, FontID.BOLD_12),
    RUNESCAPE_BOLD("RuneScape Bold", FontID.BOLD_12, FontID.BOLD_12, FontID.BOLD_12),
    VERDANA("Verdana", FontID.VERDANA_11, FontID.VERDANA_13, FontID.VERDANA_15),
    VERDANA_BOLD("Verdana Bold", FontID.VERDANA_11_BOLD, FontID.VERDANA_13_BOLD, FontID.VERDANA_13_BOLD),
    TAHOMA("Tahoma", FontID.TAHOMA_11, FontID.TAHOMA_11, FontID.TAHOMA_11),
    QUILL("Quill", FontID.QUILL_8, FontID.QUILL_MEDIUM, FontID.QUILL_CAPS_LARGE),
    FAIRY("Fairy", FontID.FAIRY_SMALL, FontID.FAIRY_SMALL, FontID.FAIRY_LARGE),
    BARBARIAN("Barbarian", FontID.BARBARIAN, FontID.BARBARIAN, FontID.BARBARIAN),
    SUROK("Surok", FontID.SUROK, FontID.SUROK, FontID.SUROK);

    /** The font the chatbox uses for messages when nothing is changed. */
    static final int GAME_DEFAULT = FontID.PLAIN_12;

    private final String displayName;
    private final int small;
    private final int medium;
    private final int large;

    ChatFont(String displayName, int small, int medium, int large)
    {
        this.displayName = displayName;
        this.small = small;
        this.medium = medium;
        this.large = large;
    }

    int fontId(Size size)
    {
        switch (size)
        {
            case SMALL:
                return small;
            case LARGE:
                return large;
            default:
                return medium;
        }
    }

    /**
     * How tall a chat line needs to be for a font, in pixels. The game's private message lines
     * are 13 pixels tall; fonts that fit in that need no extra room.
     *
     * <p>These are fixed values rather than measured ones: the height the game reports for some
     * fonts (Verdana in particular) is far larger than the letters, which left big gaps between
     * lines.
     */
    static int lineHeight(int fontId)
    {
        switch (fontId)
        {
            case FontID.VERDANA_13:
            case FontID.VERDANA_13_BOLD:
            case FontID.SUROK:
                return 15;
            case FontID.QUILL_MEDIUM:
            case FontID.BARBARIAN:
                return 16;
            case FontID.VERDANA_15:
                return 17;
            case FontID.FAIRY_LARGE:
                return 18;
            case FontID.QUILL_CAPS_LARGE:
                return 20;
            default:
                return 13;
        }
    }

    @Override
    public String toString()
    {
        return displayName;
    }

    enum Size
    {
        SMALL("Small"),
        MEDIUM("Medium"),
        LARGE("Large");

        private final String displayName;

        Size(String displayName)
        {
            this.displayName = displayName;
        }

        @Override
        public String toString()
        {
            return displayName;
        }
    }
}
