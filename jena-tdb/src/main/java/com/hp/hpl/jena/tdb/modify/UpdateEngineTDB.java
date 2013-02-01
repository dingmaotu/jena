/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hp.hpl.jena.tdb.modify;

import com.hp.hpl.jena.sparql.engine.binding.Binding ;
import com.hp.hpl.jena.sparql.modify.UpdateEngine ;
import com.hp.hpl.jena.sparql.modify.UpdateEngineFactory ;
import com.hp.hpl.jena.sparql.modify.UpdateEngineMain ;
import com.hp.hpl.jena.sparql.modify.UpdateEngineRegistry ;
import com.hp.hpl.jena.sparql.modify.UpdateEngineStreaming ;
import com.hp.hpl.jena.sparql.util.Context ;
import com.hp.hpl.jena.tdb.store.DatasetGraphTDB ;
import com.hp.hpl.jena.update.GraphStore ;
import com.hp.hpl.jena.update.UpdateRequest ;

public class UpdateEngineTDB extends UpdateEngineMain
{
    public UpdateEngineTDB(DatasetGraphTDB graphStore, UpdateRequest request, Binding inputBinding, Context context)
    { super(graphStore, request, inputBinding, context) ; }
    
    public UpdateEngineTDB(DatasetGraphTDB graphStore, Binding inputBinding, Context context)
    { super(graphStore, inputBinding, context) ; }
    

    // ---- Factory
    public static UpdateEngineFactory getFactory() { 
        return new UpdateEngineFactory()
        {
            @Override
            public boolean accept(UpdateRequest request, GraphStore graphStore, Context context)
            {
                return (graphStore instanceof DatasetGraphTDB) ;
            }
        
            @Override
            public UpdateEngine create(UpdateRequest request, GraphStore graphStore, Binding inputBinding, Context context)
            {
                return new UpdateEngineTDB((DatasetGraphTDB)graphStore, request, inputBinding, context) ;
            }

            @Override
            public boolean acceptStreaming(GraphStore graphStore, Context context)
            {
                return (graphStore instanceof DatasetGraphTDB) ;
            }
            
            @Override
            public UpdateEngineStreaming createStreaming(GraphStore graphStore, Binding inputBinding, Context context)
            {
                return new UpdateEngineTDB((DatasetGraphTDB)graphStore, inputBinding, context);
            }
        } ;
    }

    public static void register() { UpdateEngineRegistry.get().add(getFactory()) ; }
}
