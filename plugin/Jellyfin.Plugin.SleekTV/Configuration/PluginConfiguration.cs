using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.SleekTV.Configuration;

/// <summary>
/// Every layout option the app understands. Defaults match the app's built-in defaults.
/// Sizes are in dp (layout) or sp (text) on the TV.
/// </summary>
public class PluginConfiguration : BasePluginConfiguration
{
    // Colors
    public string AccentColor { get; set; } = "#E50914";
    public string BackgroundColor { get; set; } = "#000000";
    public string SurfaceColor { get; set; } = "#141414";
    public string TextColor { get; set; } = "#FFFFFF";

    // Text and headers
    public double FontScale { get; set; } = 1.0;
    public int HeroTitleSize { get; set; } = 40;
    public int SectionTitleSize { get; set; } = 22;
    public int DetailTitleSize { get; set; } = 44;

    /// <summary>Floating, Solid or Hidden.</summary>
    public string HeaderStyle { get; set; } = "Floating";

    // Home screen
    public bool ShowHero { get; set; } = true;
    public int HeroHeight { get; set; } = 380;
    public int HeroAutoRotateSeconds { get; set; } = 8;

    /// <summary>ContinueWatching (in-progress + Next Up, falls back to Latest) or Latest.</summary>
    public string HeroSource { get; set; } = "ContinueWatching";
    public string HomeRows { get; set; } = "ContinueWatching,NextUp,Latest,Discover,Libraries";
    public int LatestItemsPerLibrary { get; set; } = 20;

    // Cards
    public int PosterWidth { get; set; } = 150;
    public string PosterStyle { get; set; } = "Portrait";
    public string ContinueWatchingStyle { get; set; } = "Landscape";
    public int CardCornerRadius { get; set; } = 10;
    public int RowSpacing { get; set; } = 28;
    public int CardSpacing { get; set; } = 16;
    public double FocusScale { get; set; } = 1.08;
    public bool ShowTitlesUnderCards { get; set; } = true;
    public bool ShowWatchedBadges { get; set; } = true;

    // Episodes
    public string EpisodeLayout { get; set; } = "List";
    public int EpisodeGridColumns { get; set; } = 4;
    public int EpisodeThumbWidth { get; set; } = 280;
    public string SeasonDisplay { get; set; } = "Tabs";
    public string EpisodeSort { get; set; } = "Ascending";
    public bool ShowEpisodeOverview { get; set; } = true;

    // Details page
    public bool ShowCast { get; set; } = true;
    public bool ShowSimilar { get; set; } = true;

    // Player: skip intro/credits using Intro Skipper's segments. Button | Auto | Off.
    public string SkipIntroMode { get; set; } = "Button";
    public string SkipCreditsMode { get; set; } = "Button";
    public int SkipButtonSeconds { get; set; } = 10;

    // Seerr. The URL and key never leave the server; the app talks to Seerr through this plugin.
    public string SeerrUrl { get; set; } = string.Empty;
    public string SeerrApiKey { get; set; } = string.Empty;
    public bool ShowSeerrOnHome { get; set; } = true;

    // CSS-style overrides applied by the app on top of everything above.
    public string CustomStyles { get; set; } = string.Empty;
}
