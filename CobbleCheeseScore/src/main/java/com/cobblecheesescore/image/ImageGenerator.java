package com.cobblecheesescore.image;

import com.cobblecheesescore.config.Config;
import com.cobblecheesescore.config.ScoreboardConfig;
import com.cobblecheesescore.scoreboard.PlayerScore;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ImageGenerator {
    private final Config config;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    
    // Constantes de design professionnel
    private static final int COLUMN_WIDTH = 580;
    private static final int ROW_HEIGHT = 60;
    private static final int HEADER_HEIGHT = 140;
    private static final int FOOTER_HEIGHT = 60;
    private static final int PADDING = 40;
    private static final int COLUMN_GAP = 30;
    
    public ImageGenerator(Config config) {
        this.config = config;
    }
    
    public BufferedImage generateScoreboard(ScoreboardConfig boardConfig, List<PlayerScore> scores) {
        return generateScoreboard(boardConfig, scores, null);
    }

    /** Render with an optional dynamic title override (used by Conseil 4 ranked-failures
     *  board where the title reflects the current champion's name). Pass null to use
     *  boardConfig.getTitle() as before. */
    public BufferedImage generateScoreboard(ScoreboardConfig boardConfig, List<PlayerScore> scores,
                                              String dynamicTitle) {
        int numPlayers = Math.min(scores.size(), boardConfig.getMaxPlayers());
        int rowsPerColumn = boardConfig.getRowsPerColumn();
        
        // Calculer le nombre de colonnes nécessaires (jusqu'à 3 colonnes)
        int columns = (int) Math.ceil((double) numPlayers / rowsPerColumn);
        columns = Math.max(1, Math.min(columns, 3)); // Maximum 3 colonnes (au lieu de 2)
        
        // Calculer les dimensions
        int actualRowsPerColumn = (int) Math.ceil((double) numPlayers / columns);
        int width = (COLUMN_WIDTH * columns) + (COLUMN_GAP * (columns - 1)) + (PADDING * 2);
        int height = HEADER_HEIGHT + (actualRowsPerColumn * ROW_HEIGHT) + FOOTER_HEIGHT + (PADDING * 2);
        
        // Créer l'image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        
        // Activer l'antialiasing pour un rendu professionnel
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        // Récupérer les couleurs
        Color bgColor = parseColor(boardConfig.getColorOrDefault("background", config.getDefaultColors()));
        Color titleBgColor = parseColor(boardConfig.getColorOrDefault("titleBackground", config.getDefaultColors()));
        
        // Use custom titleText color if set, otherwise auto-calculate from background luminosity
        String customTitleText = boardConfig.getColors().get("titleText");
        Color titleTextColor = (customTitleText != null && !customTitleText.isEmpty())
            ? parseColor(customTitleText)
            : getContrastingTextColor(titleBgColor);
        
        Color textColor = parseColor(boardConfig.getColorOrDefault("text", config.getDefaultColors()));
        Color footerTextColor = parseColor(boardConfig.getColorOrDefault("footerText", config.getDefaultColors()));
        
        // Fond avec gradient subtil
        GradientPaint bgGradient = new GradientPaint(
            0, 0, bgColor,
            width, height, adjustBrightness(bgColor, 0.9f)
        );
        g.setPaint(bgGradient);
        g.fillRect(0, 0, width, height);
        
        // Header avec gradient
        GradientPaint headerGradient = new GradientPaint(
            0, 0, titleBgColor,
            width, HEADER_HEIGHT, adjustBrightness(titleBgColor, 0.85f)
        );
        g.setPaint(headerGradient);
        g.fillRoundRect(PADDING, PADDING, width - (PADDING * 2), HEADER_HEIGHT, 20, 20);
        
        // Titre avec couleur automatique (noir ou blanc selon le fond)
        g.setColor(titleTextColor);
        g.setFont(new Font("Inter", Font.BOLD, 48));
        FontMetrics titleFm = g.getFontMetrics();
        String effectiveTitle = (dynamicTitle != null && !dynamicTitle.isBlank())
                ? dynamicTitle : boardConfig.getTitle();
        int titleX = (width - titleFm.stringWidth(effectiveTitle)) / 2;
        g.drawString(effectiveTitle, titleX, PADDING + 60);
        
        // Sous-titre avec la même couleur
        g.setFont(new Font("Inter", Font.PLAIN, 20));
        FontMetrics subtitleFm = g.getFontMetrics();
        int subtitleX = (width - subtitleFm.stringWidth(boardConfig.getDescription())) / 2;
        g.drawString(boardConfig.getDescription(), subtitleX, PADDING + 100);
        
        // Dessiner les joueurs en colonnes
        int yStart = HEADER_HEIGHT + PADDING + 20;
        
        for (int col = 0; col < columns; col++) {
            int xOffset = PADDING + (col * (COLUMN_WIDTH + COLUMN_GAP));
            int startIdx = col * actualRowsPerColumn;
            int endIdx = Math.min(startIdx + actualRowsPerColumn, numPlayers);
            
            for (int i = startIdx; i < endIdx; i++) {
                PlayerScore player = scores.get(i);
                int rank = i + 1;
                int rowIdx = i - startIdx;
                int yOffset = yStart + (rowIdx * ROW_HEIGHT);
                
                // Fond de la ligne
                Color rowBgColor = getRowBackgroundColor(rank, boardConfig);
                g.setColor(rowBgColor);
                g.fillRoundRect(xOffset, yOffset, COLUMN_WIDTH, ROW_HEIGHT - 8, 12, 12);
                
                // Bordure gauche pour le top 3
                if (rank <= 3) {
                    Color borderColor = getRankBorderColor(rank);
                    g.setColor(borderColor);
                    g.fillRoundRect(xOffset, yOffset, 4, ROW_HEIGHT - 8, 2, 2);
                }
                
                // Numéro de rang
                g.setColor(getRankNumberColor(rank));
                g.setFont(new Font("Inter", Font.BOLD, rank <= 3 ? 32 : 28));
                String rankText = "#" + rank;
                g.drawString(rankText, xOffset + 20, yOffset + 40);
                
                // Nom du joueur
                g.setColor(rank == 1 ? new Color(255, 215, 0) : textColor);
                g.setFont(new Font("Inter", Font.BOLD, rank == 1 ? 22 : 18));
                FontMetrics nameFm = g.getFontMetrics();
                
                String playerName = player.getPlayerName();
                int maxNameWidth = COLUMN_WIDTH - 260;
                while (nameFm.stringWidth(playerName) > maxNameWidth && playerName.length() > 10) {
                    playerName = playerName.substring(0, playerName.length() - 4) + "...";
                }
                
                g.drawString(playerName, xOffset + 100, yOffset + 38);
                
                // Score
                Color scoreColor = parseColor(boardConfig.getColorOrDefault("titleBackground", config.getDefaultColors()));
                g.setColor(scoreColor);
                g.setFont(new Font("Inter", Font.BOLD, 22));
                FontMetrics scoreFm = g.getFontMetrics();
                String scoreText = player.getFormattedScore();
                int scoreX = xOffset + COLUMN_WIDTH - scoreFm.stringWidth(scoreText) - 20;
                g.drawString(scoreText, scoreX, yOffset + 38);
            }
        }
        
        // Footer
        if (config.isShowLastUpdate()) {
            g.setColor(footerTextColor);
            g.setFont(new Font("Inter", Font.PLAIN, 16));
            
            LocalDateTime now = LocalDateTime.now(ZoneId.of(config.getTimeZone()));
            String timeStr = now.format(TIME_FORMATTER);
            String updateText = config.getLastUpdateText() + " " + timeStr;
            
            FontMetrics footerFm = g.getFontMetrics();
            int footerX = (width - footerFm.stringWidth(updateText)) / 2;
            int footerY = height - 30;
            
            g.drawString(updateText, footerX, footerY);
        }
        
        g.dispose();
        return image;
    }
    
    /**
     * Calcule automatiquement la couleur du texte (noir ou blanc) selon la luminosité du fond
     * Utilise la formule de luminosité perçue (YIQ)
     */
    private Color getContrastingTextColor(Color backgroundColor) {
        // Formule de luminosité perçue
        double luminance = (0.299 * backgroundColor.getRed() + 
                           0.587 * backgroundColor.getGreen() + 
                           0.114 * backgroundColor.getBlue()) / 255.0;
        
        // Si le fond est clair (luminosité > 0.5), utiliser du texte noir
        // Sinon, utiliser du texte blanc
        if (luminance > 0.5) {
            return new Color(20, 20, 20); // Noir presque noir (plus doux que #000000)
        } else {
            return new Color(255, 255, 255); // Blanc
        }
    }
    
    private Color getRowBackgroundColor(int rank, ScoreboardConfig config) {
        Color baseColor = new Color(30, 35, 60, 160);
        
        if (rank == 1) {
            // Or avec transparence
            return new Color(255, 215, 0, 40);
        } else if (rank == 2) {
            // Argent avec transparence
            return new Color(192, 192, 192, 30);
        } else if (rank == 3) {
            // Bronze avec transparence
            return new Color(205, 127, 50, 30);
        }
        
        return baseColor;
    }
    
    private Color getRankBorderColor(int rank) {
        return switch (rank) {
            case 1 -> new Color(255, 215, 0);  // Or
            case 2 -> new Color(192, 192, 192); // Argent
            case 3 -> new Color(205, 127, 50);  // Bronze
            default -> new Color(255, 255, 255, 50);
        };
    }
    
    private Color getRankNumberColor(int rank) {
        return switch (rank) {
            case 1 -> new Color(255, 215, 0);   // Or
            case 2 -> new Color(192, 192, 192);  // Argent
            case 3 -> new Color(205, 127, 50);   // Bronze
            default -> new Color(255, 255, 255, 128);
        };
    }
    
    private Color parseColor(String hex) {
        try {
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (Exception e) {
            return Color.WHITE;
        }
    }
    
    private Color adjustBrightness(Color color, float factor) {
        int r = Math.min(255, (int) (color.getRed() * factor));
        int g = Math.min(255, (int) (color.getGreen() * factor));
        int b = Math.min(255, (int) (color.getBlue() * factor));
        return new Color(r, g, b, color.getAlpha());
    }
}
