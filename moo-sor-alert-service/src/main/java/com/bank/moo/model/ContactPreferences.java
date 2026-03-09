package com.bank.moo.model;

import java.util.List;

public class ContactPreferences {

    private List<ChannelPreference> channels;

    public ContactPreferences() {}

    public List<ChannelPreference> getChannels() { return channels; }
    public void setChannels(List<ChannelPreference> channels) { this.channels = channels; }
}
