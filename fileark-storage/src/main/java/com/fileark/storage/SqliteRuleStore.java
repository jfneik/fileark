package com.fileark.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.rule.ActionType;
import com.fileark.core.rule.Condition;
import com.fileark.core.rule.FieldType;
import com.fileark.core.rule.Operator;
import com.fileark.core.rule.Rule;
import com.fileark.core.rule.RuleAction;
import com.fileark.core.port.RuleStore;

import java.util.ArrayList;
import java.util.List;

public final class SqliteRuleStore implements RuleStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database db;

    public SqliteRuleStore(Database db) {
        this.db = db;
    }

    @Override
    public long create(Rule rule) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "INSERT INTO rule(name, priority, enabled, conditions_json, action_json) " +
                             "VALUES (?, ?, ?, ?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rule.name());
            ps.setInt(2, rule.priority());
            ps.setInt(3, rule.enabled() ? 1 : 0);
            ps.setString(4, JSON.writeValueAsString(rule.conditions()));
            ps.setString(5, JSON.writeValueAsString(rule.action()));
            ps.executeUpdate();
            return SqliteScanStore.generatedKey(ps);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void update(Rule rule) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "UPDATE rule SET name = ?, priority = ?, enabled = ?, conditions_json = ?, action_json = ? " +
                             "WHERE id = ?")) {
            ps.setString(1, rule.name());
            ps.setInt(2, rule.priority());
            ps.setInt(3, rule.enabled() ? 1 : 0);
            ps.setString(4, JSON.writeValueAsString(rule.conditions()));
            ps.setString(5, JSON.writeValueAsString(rule.action()));
            ps.setLong(6, rule.id());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void delete(long id) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("DELETE FROM rule WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<Rule> all() {
        try (var conn = db.open();
             var rs = conn.createStatement().executeQuery("SELECT * FROM rule ORDER BY priority")) {
            List<Rule> rules = new ArrayList<>();
            while (rs.next()) {
                List<Condition> conditions = List.of(JSON.readValue(rs.getString("conditions_json"),
                        Condition[].class));
                var actionRaw = JSON.readTree(rs.getString("action_json"));
                RuleAction action = new RuleAction(
                        ActionType.valueOf(actionRaw.get("type").asText()),
                        actionRaw.path("targetTemplate").asText(null),
                        actionRaw.path("tag").asText(null));
                rules.add(new Rule(rs.getLong("id"), rs.getString("name"), rs.getInt("priority"),
                        rs.getInt("enabled") == 1, conditions, action));
            }
            return rules;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
