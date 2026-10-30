using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Jellyfin.Plugin.SleekTV.Configuration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.SleekTV.Api;

/// <summary>
/// Proxies a small set of Seerr (Jellyseerr/Overseerr) API calls for the TV app.
/// The Seerr API key stays on the server. Requests are made on behalf of the signed-in
/// Jellyfin user (via X-Api-User), so Seerr's per-user permissions and quotas apply.
/// </summary>
[ApiController]
[Authorize]
[Route("SleekTV/Seerr")]
public class SeerrController : ControllerBase
{
    // Jellyfin user id -> Seerr user id.
    private static readonly ConcurrentDictionary<Guid, int> UserMap = new();

    private readonly IHttpClientFactory _httpClientFactory;

    public SeerrController(IHttpClientFactory httpClientFactory)
    {
        _httpClientFactory = httpClientFactory;
    }

    private static PluginConfiguration Config => Plugin.Instance?.Configuration ?? new PluginConfiguration();

    public static bool IsConfigured(PluginConfiguration c) =>
        !string.IsNullOrWhiteSpace(c.SeerrUrl) && !string.IsNullOrWhiteSpace(c.SeerrApiKey);

    private static HttpRequestMessage Build(HttpMethod method, string path)
    {
        var c = Config;
        var request = new HttpRequestMessage(method, c.SeerrUrl.Trim().TrimEnd('/') + "/api/v1/" + path);
        request.Headers.Add("X-Api-Key", c.SeerrApiKey.Trim());
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return request;
    }

    private HttpClient Client()
    {
        var client = _httpClientFactory.CreateClient();
        client.Timeout = TimeSpan.FromSeconds(20);
        return client;
    }

    private async Task<ActionResult> Forward(HttpRequestMessage request)
    {
        if (!IsConfigured(Config))
        {
            return NotFound(new { message = "Seerr isn't set up in the SleekTV plugin." });
        }

        try
        {
            using var response = await Client().SendAsync(request).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            return new ContentResult { Content = body, ContentType = "application/json", StatusCode = (int)response.StatusCode };
        }
        catch (Exception e)
        {
            return StatusCode(502, new { message = "Couldn't reach Seerr: " + e.Message });
        }
    }

    /// <summary>Is Seerr configured and reachable with the saved key? Used by the settings page's Test button.</summary>
    [HttpGet("Status")]
    public async Task<ActionResult> Status()
    {
        if (!IsConfigured(Config))
        {
            return Ok(new { configured = false, reachable = false, message = "Not configured" });
        }

        try
        {
            using var response = await Client().SendAsync(Build(HttpMethod.Get, "auth/me")).ConfigureAwait(false);
            var ok = response.IsSuccessStatusCode;
            var message = ok ? "Connected" : $"Seerr answered {(int)response.StatusCode}. Check the API key.";
            return Ok(new { configured = true, reachable = ok, message });
        }
        catch (Exception e)
        {
            return Ok(new { configured = true, reachable = false, message = "Couldn't reach Seerr: " + e.Message });
        }
    }

    [HttpGet("Search")]
    public Task<ActionResult> Search([FromQuery] string query, [FromQuery] int page = 1) =>
        Forward(Build(HttpMethod.Get, $"search?query={Uri.EscapeDataString(query ?? string.Empty)}&page={Math.Max(1, page)}"));

    [HttpGet("Discover/{kind}")]
    public Task<ActionResult> Discover([FromRoute] string kind, [FromQuery] int page = 1)
    {
        var path = kind.ToLowerInvariant() switch
        {
            "trending" => "discover/trending",
            "movies" => "discover/movies",
            "tv" => "discover/tv",
            "upcoming" => "discover/movies/upcoming",
            _ => null,
        };
        if (path is null)
        {
            return Task.FromResult<ActionResult>(NotFound());
        }

        return Forward(Build(HttpMethod.Get, $"{path}?page={Math.Max(1, page)}"));
    }

    [HttpGet("Movie/{tmdbId:int}")]
    public Task<ActionResult> Movie([FromRoute] int tmdbId) => Forward(Build(HttpMethod.Get, $"movie/{tmdbId}"));

    [HttpGet("Tv/{tmdbId:int}")]
    public Task<ActionResult> Tv([FromRoute] int tmdbId) => Forward(Build(HttpMethod.Get, $"tv/{tmdbId}"));

    public class RequestBody
    {
        public string MediaType { get; set; } = "movie";

        public int MediaId { get; set; }
    }

    /// <summary>Request a movie, or every season of a show, as the signed-in Jellyfin user.</summary>
    [HttpPost("Request")]
    public async Task<ActionResult> CreateRequest([FromBody] RequestBody body)
    {
        if (!IsConfigured(Config))
        {
            return NotFound(new { message = "Seerr isn't set up in the SleekTV plugin." });
        }

        var isTv = string.Equals(body.MediaType, "tv", StringComparison.OrdinalIgnoreCase);
        if (!isTv && !string.Equals(body.MediaType, "movie", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new { message = "mediaType must be movie or tv" });
        }

        var seerrUserId = await FindSeerrUser().ConfigureAwait(false);
        if (seerrUserId is null)
        {
            return StatusCode(403, new
            {
                message = "Your Jellyfin account isn't in Seerr yet. Ask the admin to import it (Seerr → Users → Import Jellyfin Users).",
            });
        }

        var payload = new JsonObject
        {
            ["mediaType"] = isTv ? "tv" : "movie",
            ["mediaId"] = body.MediaId,
        };
        if (isTv)
        {
            payload["seasons"] = "all";
        }

        var request = Build(HttpMethod.Post, "request");
        request.Headers.Add("X-Api-User", seerrUserId.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
        request.Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json");
        return await Forward(request).ConfigureAwait(false);
    }

    /// <summary>Matches the signed-in Jellyfin user to their Seerr account by Jellyfin user id.</summary>
    private async Task<int?> FindSeerrUser()
    {
        var claim = User.FindFirst("Jellyfin-UserId")?.Value;
        if (!Guid.TryParse(claim, out var jellyfinUserId))
        {
            return null;
        }

        if (UserMap.TryGetValue(jellyfinUserId, out var cached))
        {
            return cached;
        }

        try
        {
            using var response = await Client().SendAsync(Build(HttpMethod.Get, "user?take=1000&skip=0")).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync().ConfigureAwait(false));
            if (!doc.RootElement.TryGetProperty("results", out var results))
            {
                return null;
            }

            foreach (var user in results.EnumerateArray())
            {
                if (user.TryGetProperty("jellyfinUserId", out var jf)
                    && jf.ValueKind == JsonValueKind.String
                    && Guid.TryParse(jf.GetString(), out var id)
                    && user.TryGetProperty("id", out var seerrId)
                    && seerrId.TryGetInt32(out var seerrUserId))
                {
                    UserMap[id] = seerrUserId;
                }
            }
        }
        catch (Exception)
        {
            return null;
        }

        return UserMap.TryGetValue(jellyfinUserId, out var found) ? found : null;
    }
}
