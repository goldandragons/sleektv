using System.Text.Json;
using Jellyfin.Plugin.SleekTV.Configuration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.SleekTV.Api;

/// <summary>
/// Lets any signed-in user (not just admins) read the layout, so the TV app can fetch it.
/// GET /SleekTV/Layout
/// </summary>
[ApiController]
[Authorize]
[Route("SleekTV")]
public class SleekTvController : ControllerBase
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    [HttpGet("Layout")]
    [Produces("application/json")]
    public ActionResult GetLayout()
    {
        var config = Plugin.Instance?.Configuration ?? new PluginConfiguration();
        var node = JsonSerializer.SerializeToNode(config, JsonOptions)!.AsObject();

        // Never send Seerr credentials to clients; just say whether Seerr is set up.
        node.Remove("seerrUrl");
        node.Remove("seerrApiKey");
        node["seerrEnabled"] = SeerrController.IsConfigured(config);

        return Content(node.ToJsonString(), "application/json");
    }
}
