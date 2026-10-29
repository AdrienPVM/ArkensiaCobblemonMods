[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](minigames.fr.md)

# Mini-games

The mini-game subsystem has nothing in common with the leaderboards: no world data, no
rendered image, no configuration entry. It is a small ledger of event results that an
admin fills in by hand after running a trésor hunt, a race, a tournament — anything where
people finish in an order.

Its reason for existing is that the leaderboards can only measure what Cobblemon and
vanilla already count. An event has no counter, so somebody has to type the result in.

---

## The model

```text
MiniGameManager                config/cobblecheesescore_minigames.json
 └── games : Map<gameId, MiniGame>       insertion-ordered
       ├── displayName : String
       └── sessions : List<GameSession>  chronological, capped at 500
             ├── date : long             epoch ms, set at insertion
             └── rankings : List<SessionEntry>
                   └── uuid · name · score (double)

MiniGameLegends                config/cobblecheesescore_legends.json
 └── records : List<LegendRecord>        chronological, never pruned
       gameId · gameName · date · winnerUuid · winnerName · winnerScore
       · firstEverForGame · podium (top 3)
```

A game is an identifier and a label. A session is one running of that game, with the
players who took part and their raw scores. Nothing links a session to an in-game event,
a time, or a location: the date is simply when the result was submitted.

`MiniGameManager` is constructed during `onInitializeServer`, before the world exists,
and loads its file right there. Mini-games therefore work on a server whose world folder
has no Cobblemon data at all.

## Scoring

The raw scores in a session are stored but never ranked. What produces a ranking is a
podium-points rule applied to the **array order as submitted**:

| Position in the submitted array | Points |
|---|---|
| 1st | 3 |
| 2nd | 2 |
| 3rd | 1 |
| 4th and below | 0 |

Points accumulate across sessions. `formattedScore` is `"<n> pts"`, sorted descending.

That has two consequences worth planning around.

**The submitter decides the order.** `POST /minigames/:id/sessions` stores the array
exactly as sent, and `computePointsFromSessions` takes indices 0, 1 and 2. Submit a
session sorted ascending by time in a race where the fastest wins and you have just given
three points to the slowest player. Nothing will warn you; the raw `score` values are
inert.

**Everyone below third place is invisible.** They are stored in the session and appear in
`GET /minigames/:id`, but they contribute nothing to a ranking, so a player who always
finishes fourth never appears in the aggregate. `playerCount` counts players who have
been on a podium at least once, not participants.

Two players tied on points are ordered by whatever `Collections.sort` does with equal
keys — stable, so by first appearance in the points map, which is the order the sessions
were walked. There is no tie-break.

## Per-game and global rankings

`computeGameRanking(gameId)` applies the rule to one game's sessions.
`computeGlobalRanking()` concatenates every game's sessions and applies the same rule, so
a game with 40 sessions dominates a game with 3. Weighting is not configurable.

Both are computed on demand, on the HTTP thread, every time a route asks. Nothing is
cached. With 500 sessions per game and a handful of games that is still trivially cheap.

## The 500-session cap and the legends archive

`addSession` appends, then drops entries off the front until the game holds at most 500
sessions. Dropped sessions are gone from `cobblecheesescore_minigames.json` and gone from
the rankings: an aggregate ranking is a rolling window of the last 500 results, not a
lifetime total.

After trimming and saving, `addSession` also hands the result to
`MiniGameLegends.recordSession`, which appends a permanent record to
`config/cobblecheesescore_legends.json`. The archive gets every session, including the
ones the cap later rotates out:

```json
{
  "records": [
    {
      "gameId": "chasse_au_tresor",
      "gameName": "Chasse au trésor",
      "date": 1761700000000,
      "winnerUuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "winnerName": "RoiCheese",
      "winnerScore": 420.0,
      "firstEverForGame": true,
      "podium": [
        { "rank": 1, "uuid": "5e1b2c3d-…", "name": "RoiCheese", "score": 420.0 },
        { "rank": 2, "uuid": "0b1c2d3e-…", "name": "Alex",      "score": 310.0 }
      ]
    }
  ]
}
```

That file is written with a temp-file-plus-atomic-move, so a crash mid-write cannot
truncate it — the only file in this mod that takes that precaution. Nothing prunes it,
nothing deletes from it, and deleting a game or a session does not touch it. It is the
permanent record of who won what.

⚠️ **No route and no command reads the legends file.** `MiniGameLegends.getAll()`,
`getByGameId()` and `MiniGameManager.getLegends()` have no callers. The archive is
write-only from the outside: to consult it you open the JSON. `firstEverForGame` and
`isFirstEverForGame()` are computed and stored for a consumer that does not exist yet.

An empty `rankings` array is stored as a session but skipped by the archive, which
returns immediately on an empty list.

## Running one end to end

There is no command for any of this; the only way in is the dashboard or the REST API.
Full request and response shapes in
[dashboard](dashboard.md#mini-game-routes).

1. Create the game once, with an id you can type in a URL — lowercase, no spaces, no
   slashes. `POST /minigames` with `{"id":"chasse_au_tresor","displayName":"Chasse au
   trésor"}`.
2. Run the event in-game. Note who finished where and each player's UUID. The UUID is
   what identifies a player across sessions; the name is only a label, and a rename makes
   the newest submitted name win in the ranking while the old one stays in the stored
   sessions.
3. Submit the result with `POST /minigames/:id/sessions`, **best first**.
4. Check it with `GET /minigames/:id`. The new session is the first element of
   `sessions`, and the aggregate `ranking` has moved.
5. Fix a mistake with `DELETE /minigames/:id/sessions/:idx`, using the `index` from the
   session object, then resubmit. Deleting shifts every higher index down by one, so
   re-fetch between deletions.

A session submitted with the wrong order cannot be reordered in place. Delete it and
submit it again.

## Known gaps

- Nothing records *who* submitted a session. CheeseDash's own audit log notes the request,
  but the stored session carries no author.
- Nothing validates that a `uuid` is a real player, or even a UUID. A typo creates a
  distinct entrant with its own points.
- Game ids are unvalidated. One containing `/` makes every `/minigames/:id` route
  unreachable for that game; one equal to `global` is shadowed by the global-ranking
  route for `GET` (its `PUT` and `DELETE` still work).
- `MiniGameManager.save()` rewrites the entire file on every mutation, not atomically.
  A crash during the write can leave a truncated `cobblecheesescore_minigames.json`,
  which `load()` will then fail on, log, and start empty from. The legends file is the
  fallback copy of the data in that case, but restoring from it is a manual job.

---

← [PNG export](png-export.md) · [Dashboard API](dashboard.md) →
