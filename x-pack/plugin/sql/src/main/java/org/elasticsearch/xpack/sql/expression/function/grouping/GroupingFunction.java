/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.grouping;

import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.Function;
import org.elasticsearch.xpack.sql.expression.gen.pipeline.AggNameInput;
import org.elasticsearch.xpack.sql.expression.gen.pipeline.Pipe;
import org.elasticsearch.xpack.sql.expression.gen.script.ScriptTemplate;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * A type of {@code Function} that creates groups or buckets.
 */
public abstract class GroupingFunction extends Function {

    private final Expression field;
    private final List<Expression> parameters;

    private GroupingFunctionAttribute lazyAttribute;

    protected GroupingFunction(Location location, Expression field) {
        this(location, field, emptyList());
    }

    protected GroupingFunction(Location location, Expression field, List<Expression> parameters) {
        super(location, CollectionUtils.combine(singletonList(field), parameters));
        this.field = field;
        this.parameters = parameters;
    }

    public Expression field() {
        return field;
    }

    public List<Expression> parameters() {
        return parameters;
    }

    @Override
    public GroupingFunctionAttribute toAttribute() {
        if (lazyAttribute == null) {
            // this is highly correlated with QueryFolder$FoldAggregate#addAggFunction (regarding the function name within the querydsl)
            lazyAttribute = new GroupingFunctionAttribute(location(), name(), dataType(), id(), functionId());
        }
        return lazyAttribute;
    }

    @Override
    public final GroupingFunction replaceChildren(List<Expression> newChildren) {
        if (newChildren.size() != 1) {
            throw new IllegalArgumentException("expected [1] child but received [" + newChildren.size() + "]");
        }
        return replaceChild(newChildren.get(0));
    }

    protected abstract GroupingFunction replaceChild(Expression newChild);

    @Override
    protected Pipe makePipe() {
        // unresolved AggNameInput (should always get replaced by the folder)
        return new AggNameInput(location(), this, name());
    }

    @Override
    public ScriptTemplate asScript() {
        throw new SqlIllegalArgumentException("Grouping functions cannot be scripted");
    }

    @Override
    public boolean equals(Object obj) {
        if (false == super.equals(obj)) {
            return false;
        }
        GroupingFunction other = (GroupingFunction) obj;
        return Objects.equals(other.field(), field())
            && Objects.equals(other.parameters(), parameters());
    }

    @Override
    public int hashCode() {
        return Objects.hash(field(), parameters());
    }
}