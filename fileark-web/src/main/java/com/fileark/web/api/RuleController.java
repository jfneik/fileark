package com.fileark.web.api;

import com.fileark.core.port.RuleStore;
import com.fileark.core.rule.Rule;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleStore ruleStore;

    public RuleController(RuleStore ruleStore) {
        this.ruleStore = ruleStore;
    }

    @GetMapping
    public List<Rule> list() {
        return ruleStore.all();
    }

    @PostMapping
    public Rule create(@RequestBody Rule rule) {
        long id = ruleStore.create(rule);
        return new Rule(id, rule.name(), rule.priority(), rule.enabled(),
                rule.conditions(), rule.action());
    }

    @PutMapping("/{id}")
    public Rule update(@PathVariable long id, @RequestBody Rule rule) {
        Rule updated = new Rule(id, rule.name(), rule.priority(), rule.enabled(),
                rule.conditions(), rule.action());
        ruleStore.update(updated);
        return updated;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        ruleStore.delete(id);
    }
}
