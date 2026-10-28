package com.cobblecheesescore.scoreboard;

public class PlayerScore implements Comparable<PlayerScore> {
    private final String uuid;
    private final String playerName;
    private final double score;
    private final String formattedScore;
    private final boolean ascending;
    
    public PlayerScore(String uuid, String playerName, double score, String formattedScore, boolean ascending) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.score = score;
        this.formattedScore = formattedScore;
        this.ascending = ascending;
    }
    
    @Override
    public int compareTo(PlayerScore other) {
        if (ascending) {
            return Double.compare(this.score, other.score);
        } else {
            return Double.compare(other.score, this.score);
        }
    }
    
    // Getters
    public String getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }
    public double getScore() { return score; }
    public String getFormattedScore() { return formattedScore; }
    public boolean isAscending() { return ascending; }
}
