package me.skerik.justlogs.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.skerik.justlogs.model.Action;
import org.junit.jupiter.api.Test;

class QueryParserTest {

    private static final long NOW = 1_000_000;

    private static LogQuery parse(String q) throws QuerySyntaxException {
        return QueryParser.parse(q, NOW, QueryParser.NO_TAGS, 0);
    }

    @Test
    void parsesAFullQuery() throws Exception {
        LogQuery q = parse("u:Notch,jeb_ t:2d-1d r:10 a:-block b:!stone,dirt w:world_nether at:1,64,-3 #verbose");
        assertEquals(List.of("Notch", "jeb_"), q.users());
        assertFalse(q.usersNegated());
        assertEquals(NOW - 2 * 86_400, q.fromSec());
        assertEquals(NOW - 86_400, q.toSec());
        assertEquals(Radius.cube(10), q.radius());
        assertTrue(q.actions().contains(Action.BREAK));
        assertFalse(q.actions().contains(Action.PLACE));
        assertEquals(List.of("stone", "dirt"), q.materials());
        assertTrue(q.materialsNegated());
        assertEquals("world_nether", q.world());
        assertEquals(-3, q.at()[2]);
        assertTrue(q.has(LogQuery.Flag.VERBOSE));
    }

    @Test
    void aSingleTimeMeansFromThatLongAgoUntilNow() throws Exception {
        LogQuery q = parse("t:30m");
        assertEquals(NOW - 1800, q.fromSec());
        assertEquals(0, q.toSec());
    }

    @Test
    void defaultTimeAppliesOnlyWhenTheQueryHasNone() throws Exception {
        assertEquals(NOW - 259_200, QueryParser.parse("u:a", NOW, QueryParser.NO_TAGS, 259_200).fromSec());
        assertEquals(NOW - 60, QueryParser.parse("u:a t:1m", NOW, QueryParser.NO_TAGS, 259_200).fromSec());
        assertEquals("3d", QueryParser.parse("u:a", NOW, QueryParser.NO_TAGS, 259_200).timeText());
    }

    @Test
    void negatedUsersAndRadiusKeywords() throws Exception {
        assertTrue(parse("u:!Notch").usersNegated());
        assertEquals(Radius.GLOBAL, parse("r:#global").radius());
        assertEquals(Radius.CHUNK, parse("r:#chunk").radius());
        assertEquals(Radius.SELECTION, parse("r:#we").radius());
        assertEquals(List.of("#tnt"), parse("u:#tnt").users());
    }

    @Test
    void parsesEntityAndQuotedKeywordFilters() throws Exception {
        LogQuery q = parse("a:entity e:!villager,wandering_trader k:\"hello diamond world\"");
        assertEquals(List.of("villager", "wandering_trader"), q.entities());
        assertTrue(q.entitiesNegated());
        assertEquals("hello diamond world", q.keyword());
        assertTrue(q.canonical().contains("k:\"hello diamond world\""));
        assertTrue(q.canonical().contains("e:!villager,wandering_trader"));
    }

    @Test
    void tagsAreExpandedByTheResolver() throws Exception {
        LogQuery q = QueryParser.parse("b:#ores", NOW, tag -> Optional.of(Set.of("diamond_ore")), 0);
        assertEquals(List.of("diamond_ore"), q.materials());
        QuerySyntaxException e = assertThrows(QuerySyntaxException.class, () -> parse("b:#nothing"));
        assertEquals("unknown-tag", e.code());
    }

    @Test
    void rejectsGarbageWithAPreciseCode() {
        assertEquals("unknown-param", code("hello"));
        assertEquals("unknown-param", code("x:1"));
        assertEquals("unknown-flag", code("#nope"));
        assertEquals("missing-value", code("u:"));
        assertEquals("bad-time", code("t:abc"));
        assertEquals("bad-time", code("t:2d-xx"));
        assertEquals("bad-radius", code("r:-5"));
        assertEquals("bad-radius", code("r:99999999"));
        assertEquals("unknown-action", code("a:fly"));
        assertEquals("bad-position", code("at:1,2"));
        assertEquals("bad-user", code("u:no'pe"));
    }

    @Test
    void canonicalFormIsStable() throws Exception {
        assertEquals("u:Notch t:2d r:10 a:-block b:stone #preview", parse("#preview b:stone a:-block r:10 t:2d u:Notch").canonical());
    }

    @Test
    void actionGroupsFollowTheDirection() {
        Set<Action> added = ActionGroups.resolve("+block").orElseThrow();
        assertTrue(added.contains(Action.PLACE) && added.contains(Action.GROW));
        assertFalse(added.contains(Action.BREAK));
        assertTrue(ActionGroups.resolve("-container").orElseThrow().contains(Action.WITHDRAW));
        assertEquals(Set.of(Action.EXPLODE), ActionGroups.resolve("explode").orElseThrow());
        assertTrue(ActionGroups.resolve("kill").orElseThrow().contains(Action.PLAYER_DEATH));
        assertTrue(ActionGroups.resolve("nonsense").isEmpty());
        assertTrue(ActionGroups.resolve("+explode").isEmpty());
    }

    private static String code(String q) {
        return assertThrows(QuerySyntaxException.class, () -> parse(q)).code();
    }
}
