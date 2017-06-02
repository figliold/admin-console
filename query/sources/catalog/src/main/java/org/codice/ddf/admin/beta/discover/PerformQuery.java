package org.codice.ddf.admin.beta.discover;

import static org.codice.ddf.admin.beta.discover.TransformCommons.executeQuery;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.codice.ddf.admin.api.DataType;
import org.codice.ddf.admin.api.fields.FunctionField;
import org.codice.ddf.admin.api.fields.ListField;
import org.codice.ddf.admin.beta.types.MetacardAttributeField;
import org.codice.ddf.admin.beta.types.MetacardField;
import org.codice.ddf.admin.common.fields.base.BaseFunctionField;
import org.codice.ddf.admin.common.fields.base.ListFieldImpl;
import org.opengis.filter.Filter;

import com.google.common.collect.ImmutableList;

import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.Result;
import ddf.catalog.filter.FilterBuilder;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;

public class PerformQuery extends BaseFunctionField<ListField<MetacardField>> {

    public static final String NAME = "query";

    public static final String DESCRIPTION = "Executes a query against the Catalog.";

    private QueryField queryArg;

    private CatalogFramework framework;

    private FilterBuilder filterBuilder;
    private List<MetacardType> metacardTypes;

    public PerformQuery(CatalogFramework framework, FilterBuilder filterBuilder,
            List<MetacardType> metacardTypes) {
        super(NAME, DESCRIPTION);
        queryArg = new QueryField(metacardTypes);

        this.framework = framework;
        this.filterBuilder = filterBuilder;
        this.metacardTypes = metacardTypes;
        setReturnType(new ListFieldImpl<>(new MetacardField(metacardTypes)));
        updateArgumentPaths();
    }

    @Override
    public List<DataType> getArguments() {
        return ImmutableList.of(queryArg);
    }

    @Override
    public ListField<MetacardField>  performFunction() {
        Filter filter = new GraphQLFilterFactory(
                filterBuilder,
                queryArg).buildFilter();

        return executeQuery(filter, framework, metacardTypes);

    }

    @Override
    public FunctionField<ListField<MetacardField>> newInstance() {
        return new PerformQuery(framework, filterBuilder, metacardTypes);
    }
}