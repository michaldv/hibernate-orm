/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.mutation.internal;

import java.util.List;
import java.util.Map;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.mapping.RootClass;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.spi.QueryParameterImplementor;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.internal.SqmUtil;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.spi.DeleteHandler;
import org.hibernate.query.sqm.mutation.spi.HandlerCreationContext;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.query.sqm.mutation.spi.UpdateHandler;
import org.hibernate.query.sqm.sql.SqmSelectTranslation;
import org.hibernate.query.sqm.sql.SqmSelectTranslator;
import org.hibernate.query.sqm.sql.SqmTranslatorFactory;
import org.hibernate.query.sqm.tree.SqmDeleteOrUpdateStatement;
import org.hibernate.query.sqm.tree.delete.SqmDeleteStatement;
import org.hibernate.query.sqm.tree.domain.SqmSimplePath;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.query.sqm.tree.predicate.SqmBetweenPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmGroupedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInListPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmJunctivePredicate;
import org.hibernate.query.sqm.tree.predicate.SqmPredicate;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.query.sqm.tree.update.SqmUpdateStatement;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameter;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcSelect;

/**
 * @author Steve Ebersole
 */
public class SqmMutationStrategyHelper {
	/**
	 * Singleton access
	 */
	public static final SqmMutationStrategyHelper INSTANCE = new SqmMutationStrategyHelper();

	private SqmMutationStrategyHelper() {
	}

	/**
	 * Standard resolution of SqmMutationStrategy to use for a given
	 * entity hierarchy.
	 */
	public static SqmMultiTableMutationStrategy resolveStrategy(
			RootClass bootEntityDescriptor,
			EntityPersister runtimeRootEntityDescriptor,
			SessionFactoryOptions options,
			ServiceRegistry serviceRegistry) {
		// todo (6.0) : Planned support for per-entity config

		if ( options.getSqmMultiTableMutationStrategy() != null ) {
			return options.getSqmMultiTableMutationStrategy();
		}

		return serviceRegistry.getService( JdbcServices.class )
				.getJdbcEnvironment()
				.getDialect()
				.getFallbackSqmMutationStrategy( runtimeRootEntityDescriptor );
	}

	/**
	 * Specialized "Supplier" or "tri Function" for creating the
	 * fallback handler if the query matches no "special cases"
	 */
	public interface FallbackDeleteHandlerCreator {
		DeleteHandler create(
				SqmDeleteStatement sqmDelete,
				DomainParameterXref domainParameterXref,
				HandlerCreationContext creationContext);
	}

	/**
	 * Standard DeleteHandler resolution applying "special case" resolution
	 */
	public static DeleteHandler resolveDeleteHandler(
			SqmDeleteStatement sqmDelete,
			DomainParameterXref domainParameterXref,
			HandlerCreationContext creationContext,
			FallbackDeleteHandlerCreator fallbackCreator) {
		if ( sqmDelete.getWhereClause() == null ) {
			// special case : unrestricted
			// 		-> delete all rows, no need to use the id table
		}
		else {
			// if the predicate contains refers to any non-id Navigable, we will need to use the id table
			if ( ! hasNonIdReferences( sqmDelete.getWhereClause().getPredicate() ) ) {
				// special case : not restricted on non-id Navigable reference
				//		-> we can apply the original restriction to the individual
				//
				// todo (6.0) : technically non-id references where the reference is mapped to the primary table
				//		can also be handled by this special case.  Really the special case condition is "has
				//		any non-id references to Navigables not mapped to the primary table of the container"
			}
		}

		// otherwise, use the fallback....
		return fallbackCreator.create( sqmDelete, domainParameterXref, creationContext );
	}

	/**
	 * Specialized "Supplier" or "tri Function" for creating the
	 * fallback handler if the query mmatches no "special cases"
	 */
	public interface FallbackUpdateHandlerCreator {
		UpdateHandler create(
				SqmUpdateStatement sqmUpdate,
				DomainParameterXref domainParameterXref,
				HandlerCreationContext creationContext);
	}

	/**
	 * Standard UpdateHandler resolution applying "special case" resolution
	 */
	public static UpdateHandler resolveUpdateHandler(
			SqmUpdateStatement sqmUpdate,
			DomainParameterXref domainParameterXref,
			HandlerCreationContext creationContext,
			FallbackUpdateHandlerCreator fallbackCreator) {
		if ( sqmUpdate.getWhereClause() == null ) {
			// special case : unrestricted
			// 		-> delete all rows, no need to use the id table
		}
		else {
			// see if the predicate contains any non-id Navigable references
			if ( ! hasNonIdReferences( sqmUpdate.getWhereClause().getPredicate() ) ) {
				// special case : not restricted on non-id Navigable reference
				//		-> we can apply the original restriction to the individual updates without needing to use the id-table
				//
				// todo (6.0) : technically non-id references where the reference is mapped to the primary table
				//		can also be handled by this special case.  Really the special case condition is "has
				//		any non-id references to Navigables not mapped to the primary table of the container"
			}
		}

		// todo (6.0) : implement the above special cases

		// otherwise, use the fallback....
		return fallbackCreator.create( sqmUpdate, domainParameterXref, creationContext );
	}

	/**
	 * Does the given `predicate` "non-identifier Navigable references"?
	 *
	 * @see #isNonIdentifierReference
	 */
	@SuppressWarnings("WeakerAccess")
	public static boolean hasNonIdReferences(SqmPredicate predicate) {
		if ( predicate instanceof SqmGroupedPredicate ) {
			return hasNonIdReferences( ( (SqmGroupedPredicate) predicate ).getSubPredicate() );
		}

		if ( predicate instanceof SqmJunctivePredicate ) {
			return hasNonIdReferences( ( (SqmJunctivePredicate) predicate ).getLeftHandPredicate() )
					&& hasNonIdReferences( ( (SqmJunctivePredicate) predicate ).getRightHandPredicate() );
		}

		if ( predicate instanceof SqmComparisonPredicate ) {
			final SqmExpression lhs = ( (SqmComparisonPredicate) predicate ).getLeftHandExpression();
			final SqmExpression rhs = ( (SqmComparisonPredicate) predicate ).getRightHandExpression();

			return isNonIdentifierReference( lhs ) || isNonIdentifierReference( rhs );
		}

		if ( predicate instanceof SqmInListPredicate ) {
			final SqmInListPredicate<?> inPredicate = (SqmInListPredicate) predicate;
			if ( isNonIdentifierReference( inPredicate.getTestExpression() ) ) {
				return true;
			}

			for ( SqmExpression listExpression : inPredicate.getListExpressions() ) {
				if ( isNonIdentifierReference( listExpression ) ) {
					return true;
				}
			}

			return false;
		}

		if ( predicate instanceof SqmBetweenPredicate ) {
			final SqmBetweenPredicate betweenPredicate = (SqmBetweenPredicate) predicate;
			return isNonIdentifierReference( betweenPredicate.getExpression() )
					|| isNonIdentifierReference( betweenPredicate.getLowerBound() )
					|| isNonIdentifierReference( betweenPredicate.getUpperBound() );
		}

		return false;
	}

	/**
	 * Is the given `expression` a `SqmNavigableReference` that is also a reference
	 * to a non-`EntityIdentifier` `Navigable`?
	 *
	 * @see SqmSimplePath
	 */
	@SuppressWarnings("WeakerAccess")
	public static boolean isNonIdentifierReference(SqmExpression expression) {
//		if ( expression instanceof SqmNavigableReference ) {
//			return ! EntityIdentifier.class.isInstance( expression );
//		}

		return false;
	}

	/**
	 * Centralized selection of ids matching the restriction of the DELETE
	 * or UPDATE SQM query
	 */
	public static List<Object> selectMatchingIds(
			SqmDeleteOrUpdateStatement sqmDeleteStatement,
			DomainParameterXref domainParameterXref,
			ExecutionContext executionContext) {
		final SessionFactoryImplementor factory = executionContext.getSession().getFactory();
		final QueryEngine queryEngine = factory.getQueryEngine();
		final SqmTranslatorFactory sqmTranslatorFactory = queryEngine.getSqmTranslatorFactory();

		final SqmSelectTranslator selectConverter = sqmTranslatorFactory.createSelectTranslator(
				executionContext.getQueryOptions(),
				domainParameterXref,
				executionContext.getQueryParameterBindings(),
				executionContext.getLoadQueryInfluencers(),
				factory
		);

		final SqmQuerySpec sqmIdSelectQuerySpec = SqmIdSelectGenerator.generateSqmEntityIdSelect(
				sqmDeleteStatement,
				executionContext,
				factory
		);

		final SqmSelectStatement sqmIdSelect = new SqmSelectStatement( factory.getNodeBuilder() );
		//noinspection unchecked
		sqmIdSelect.setQuerySpec( sqmIdSelectQuerySpec );

		final SqmSelectTranslation sqmInterpretation = selectConverter.translate( sqmIdSelect );

		final JdbcServices jdbcServices = factory.getJdbcServices();
		final JdbcEnvironment jdbcEnvironment = jdbcServices.getJdbcEnvironment();
		final SqlAstTranslatorFactory sqlAstTranslatorFactory = jdbcEnvironment.getSqlAstTranslatorFactory();

		final JdbcSelect jdbcSelect = sqlAstTranslatorFactory.buildSelectTranslator( factory ).translate( sqmInterpretation.getSqlAst() );

		final Map<QueryParameterImplementor<?>, Map<SqmParameter, List<JdbcParameter>>> jdbcParamsXref = SqmUtil.generateJdbcParamsXref(
				domainParameterXref,
				sqmInterpretation::getJdbcParamsBySqmParam
		);

		final JdbcParameterBindings jdbcParameterBindings = SqmUtil.createJdbcParameterBindings(
				executionContext.getQueryParameterBindings(),
				domainParameterXref,
				jdbcParamsXref,
				// todo (6.0) : ugh.  this one is important
				null,
				executionContext.getSession()
		);


		return factory.getJdbcServices().getJdbcSelectExecutor().list(
				jdbcSelect,
				jdbcParameterBindings,
				executionContext,
				row -> row
		);
	}
}