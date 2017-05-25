package com.baidu.hugegraph.backend.tx;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.SplicingIdGenerator;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.IdQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.schema.SchemaElement;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.structure.HugeElement;
import com.baidu.hugegraph.structure.HugeIndex;
import com.baidu.hugegraph.structure.HugeProperty;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.type.define.IndexType;
import com.baidu.hugegraph.type.schema.IndexLabel;
import com.baidu.hugegraph.util.NumericUtil;
import com.google.common.base.Preconditions;

public class IndexTransaction extends AbstractTransaction {

    public IndexTransaction(final HugeGraph graph, BackendStore store) {
        super(graph, store);
    }

    public void updateVertexIndex(HugeVertex vertex, boolean removed) {
        // vertex index
        for (String indexName : vertex.vertexLabel().indexNames()) {
            updateIndex(indexName, vertex, removed);
        }

        // edges index
        this.updateEdgesIndex(vertex, removed);
    }

    public void updateEdgesIndex(HugeVertex vertex, boolean removed) {
        // edges index
        for (HugeEdge edge : vertex.getEdges()) {
            updateEdgeIndex(edge, removed);
        }
    }

    public void updateEdgeIndex(HugeEdge edge, boolean removed) {
        // edge index
        for (String indexName : edge.edgeLabel().indexNames()) {
            updateIndex(indexName, edge, removed);
        }
    }

    protected void updateIndex(String indexName, HugeElement element,
                               boolean removed) {
        SchemaTransaction schema = graph().schemaTransaction();
        IndexLabel indexLabel = schema.getIndexLabel(indexName);
        Preconditions.checkNotNull(indexLabel,
                "Not existed index: " + indexName);

        List<Object> propertyValues = new LinkedList<>();
        for (String field : indexLabel.indexFields()) {
            HugeProperty<Object> property = element.getProperty(field);
            Preconditions.checkNotNull(property,
                    "Not existed property: " + field);
            propertyValues.add(property.value());
        }

        Object propValue = null;
        if (indexLabel.indexType() == IndexType.SECONDARY) {
            propValue = SplicingIdGenerator.concatValues(propertyValues);
        } else {
            assert indexLabel.indexType() == IndexType.SEARCH;
            Preconditions.checkState(propertyValues.size() == 1,
                    "Expect searching by only one property");
            propValue = NumericUtil.convert2Number(propertyValues.get(0));
        }

        HugeIndex index = new HugeIndex(indexLabel);
        index.propertyValues(propValue);
        index.elementIds(element.id());

        if (!removed) {
            this.addEntry(this.serializer.writeIndex(index));
        } else {
            this.removeEntry(this.serializer.writeIndex(index));
        }
    }

    public Query query(ConditionQuery query) {
        // Condition => Entry
        ConditionQuery indexQuery = this.constructIndexQuery(query);
        Iterator<BackendEntry> entries = super.query(indexQuery).iterator();

        // Entry => Id
        Set<Id> ids = new LinkedHashSet<>();
        while (entries.hasNext()) {
            HugeIndex index = this.serializer.readIndex(entries.next());
            ids.addAll(index.elementIds());
        }
        return new IdQuery(query.resultType(), ids);
    }

    protected ConditionQuery constructIndexQuery(ConditionQuery query) {
        ConditionQuery indexQuery = null;
        SchemaElement schemaElement = null;

        SchemaTransaction schema = graph().schemaTransaction();

        boolean mustBeSearch = query.hasSearchCondition();
        Object label = query.condition(HugeKeys.LABEL);
        Preconditions.checkNotNull(label,
                "Must contain key 'label' in conditions");

        switch (query.resultType()) {
            case VERTEX:
                schemaElement = schema.getVertexLabel(label.toString());
                break;
            case EDGE:
                schemaElement = schema.getEdgeLabel(label.toString());
                break;
            default:
                throw new BackendException(
                        "Unsupported index query: " + query.resultType());
        }

        Preconditions.checkNotNull(schemaElement, "Invalid label: " + label);

        Set<String> indexNames = schemaElement.indexNames();
        logger.debug("label '{}' index names: {}", label, indexNames);
        for (String name : indexNames) {
            IndexLabel indexLabel = schema.getIndexLabel(name);

            if (query.matchUserpropKeys(indexLabel.indexFields())) {
                logger.debug("matched index fields: {} of index '{}'",
                        indexLabel.indexFields(), name);

                boolean isSearch = indexLabel.indexType() == IndexType.SEARCH;
                if (mustBeSearch && !isSearch) {
                    continue;
                }

                if (indexLabel.indexType() == IndexType.SECONDARY) {
                    String valueStr = query.userpropValuesString();
                    indexQuery = new ConditionQuery(HugeType.SECONDARY_INDEX);
                    indexQuery.eq(HugeKeys.INDEX_LABEL_NAME, name);
                    indexQuery.eq(HugeKeys.PROPERTY_VALUES, valueStr);
                } else {
                    assert indexLabel.indexType() == IndexType.SEARCH;
                    if (query.userpropConditions().size() != 1) {
                        throw new BackendException(
                                "Only support searching by one field");
                    }
                    Condition condition = query.userpropConditions().get(0);
                    assert condition instanceof Condition.Relation;
                    Condition.Relation r = (Relation) condition;
                    indexQuery = new ConditionQuery(HugeType.SEARCH_INDEX);
                    indexQuery.eq(HugeKeys.INDEX_LABEL_NAME, name);
                    indexQuery.query(new Condition.SyspropRelation(
                            HugeKeys.PROPERTY_VALUES,
                            r.relation(),
                            NumericUtil.convert2Number(r.value())));
                }
                break;
            }
        }

        if (indexQuery == null) {
            throw new BackendException("No matched index for query: " + query);
        }
        return indexQuery;
    }
}
