package com.fileark.core.port;

import com.fileark.core.rule.Rule;

import java.util.List;

public interface RuleStore {

    long create(Rule rule);

    void update(Rule rule);

    void delete(long id);

    List<Rule> all();
}
