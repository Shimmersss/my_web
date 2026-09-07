export function routeAccessDecision({
  visibility = "PUBLIC",
  level,
  trialEntry = false,
  isLoggedIn = false,
  isRoot = false,
  isMatchmakingTrial = false,
}) {
  if (trialEntry && !isLoggedIn) return "allow";
  if (!visibility) return "allow";
  if (visibility === "Admin") return isRoot ? "allow" : "forbidden";

  const accessLevel = level || (visibility === "PUBLIC" ? "PUBLIC" : "USER");
  if (isMatchmakingTrial) {
    return visibility === "Matchmaking" || accessLevel === "PUBLIC" ? "allow" : "forbidden";
  }
  if (accessLevel === "PUBLIC") return "allow";
  if (!isLoggedIn) return "login";
  if (accessLevel === "ROOT" && !isRoot) return "forbidden";
  return "allow";
}
