package com.chatmessagesandbox;

import net.runelite.api.IconID;

/**
 * Account-type icons that can be shown in front of the sender's name.
 */
enum ChatIcon
{
    NONE("None", null),
    IRONMAN("Ironman", IconID.IRONMAN),
    HARDCORE_IRONMAN("Hardcore Ironman", IconID.HARDCORE_IRONMAN),
    ULTIMATE_IRONMAN("Ultimate Ironman", IconID.ULTIMATE_IRONMAN),
    GROUP_IRONMAN("Group Ironman", IconID.GROUP_IRONMAN),
    HARDCORE_GROUP_IRONMAN("Hardcore Group Ironman", IconID.HARDCORE_GROUP_IRONMAN),
    UNRANKED_GROUP_IRONMAN("Unranked Group Ironman", IconID.UNRANKED_GROUP_IRONMAN);

    private final String displayName;
    private final IconID iconId;

    ChatIcon(String displayName, IconID iconId)
    {
        this.displayName = displayName;
        this.iconId = iconId;
    }

    /**
     * The chat tag for this icon, e.g. {@code <img=2>}, or an empty string for {@link #NONE}.
     */
    String tag()
    {
        return iconId == null ? "" : iconId.toString();
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
