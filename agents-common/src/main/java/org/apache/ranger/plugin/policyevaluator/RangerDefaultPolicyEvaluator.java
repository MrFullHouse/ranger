/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.policyevaluator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerAccessTypeDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.policyresourcematcher.RangerDefaultPolicyResourceMatcher;
import org.apache.ranger.plugin.policyresourcematcher.RangerPolicyResourceMatcher;


public class RangerDefaultPolicyEvaluator extends RangerAbstractPolicyEvaluator {
	private static final Log LOG = LogFactory.getLog(RangerDefaultPolicyEvaluator.class);

	private RangerPolicyResourceMatcher     resourceMatcher       = null;
	private List<RangerPolicyItemEvaluator> policyItemEvaluators  = null;
	private int                             customConditionsCount = 0;

	@Override
	public int getCustomConditionsCount() {
		return customConditionsCount;
	}

	@Override
	public void init(RangerPolicy policy, RangerServiceDef serviceDef, RangerPolicyEngineOptions options) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.init()");
		}

		preprocessPolicy(policy, serviceDef);

		super.init(policy, serviceDef, options);

		resourceMatcher = new RangerDefaultPolicyResourceMatcher();

		resourceMatcher.setServiceDef(serviceDef);
		resourceMatcher.setPolicyResources(policy == null ? null : policy.getResources());
		resourceMatcher.init();
		
		if(policy != null && CollectionUtils.isNotEmpty(policy.getPolicyItems())) {
			policyItemEvaluators = new ArrayList<RangerPolicyItemEvaluator>();

			for(RangerPolicyItem policyItem : policy.getPolicyItems()) {
				RangerPolicyItemEvaluator itemEvaluator = new RangerDefaultPolicyItemEvaluator(serviceDef, policy, policyItem, options);

				itemEvaluator.init();

				policyItemEvaluators.add(itemEvaluator);
				
				if(CollectionUtils.isNotEmpty(itemEvaluator.getConditionEvaluators())) {
					customConditionsCount += itemEvaluator.getConditionEvaluators().size();
				}
			}
		} else {
			policyItemEvaluators = Collections.<RangerPolicyItemEvaluator>emptyList();
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.init()");
		}
	}

    @Override
    public void evaluate(RangerAccessRequest request, RangerAccessResult result) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RangerDefaultPolicyEvaluator.evaluate(" + request + ", " + result + ")");
        }

        if (request != null && result != null) {
            boolean isResourceMatch              = false;
            boolean isResourceHeadMatch          = false;
            boolean isResourceMatchAttempted     = false;
            boolean isResourceHeadMatchAttempted = false;
            final boolean attemptResourceHeadMatch = request.isAccessTypeAny() || request.getResourceMatchingScope() == RangerAccessRequest.ResourceMatchingScope.SELF_OR_DESCENDANTS;

            if (!result.getIsAuditedDetermined()) {
                // Need to match request.resource first. If it matches (or head matches), then only more progress can be made
                if (!isResourceMatchAttempted) {
                    isResourceMatch = isMatch(request.getResource());
                    isResourceMatchAttempted = true;
                }

                // Try head match only if match was not found and ANY access was requested
                if (!isResourceMatch) {
                    if (attemptResourceHeadMatch && !isResourceHeadMatchAttempted) {
                        isResourceHeadMatch = matchResourceHead(request.getResource());
                        isResourceHeadMatchAttempted = true;
                    }
                }

                if (isResourceMatch || isResourceHeadMatch) {
                    // We are done for determining if audit is needed for this policy
                    if (isAuditEnabled()) {
                        result.setIsAudited(true);
                    }
                }
            }

            if (!result.getIsAccessDetermined()) {
                // Try Match only if it was not attempted as part of evaluating Audit requirement
                if (!isResourceMatchAttempted) {
                    isResourceMatch = isMatch(request.getResource());
                    isResourceMatchAttempted = true;
                }

                // Try Head Match only if no match was found so far AND a head match was not attempted as part of evaluating
                // Audit requirement
                if (!isResourceMatch) {
                    if (attemptResourceHeadMatch && !isResourceHeadMatchAttempted) {
                        isResourceHeadMatch = matchResourceHead(request.getResource());
	                    isResourceHeadMatchAttempted = true;
                    }
                }
                // Go further to evaluate access only if match or head match was found at this point
                if (isResourceMatch || isResourceHeadMatch) {
                    boolean isPolicyItemsMatch = isPolicyItemsMatch(request);

                    RangerPolicy policy = getPolicy();

                    if(isPolicyItemsMatch) {
                        if(policy.isPolicyTypeDeny()) {
                            if(isResourceMatch) {
                                result.setIsAllowed(false);
                                result.setPolicyId(policy.getId());
                            }
	                    } else {
	                        result.setIsAllowed(true);
	                        result.setPolicyId(policy.getId());
	                    }
                    } else {
                        if(policy.isPolicyTypeExclusiveAllow()) {
                            if(isResourceMatch) {
                                result.setIsAllowed(false);
                                result.setPolicyId(policy.getId());
                            }
                        }
                    }
                }
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerDefaultPolicyEvaluator.evaluate(" + request + ", " + result + ")");
        }
    }

    protected boolean isPolicyItemsMatch(RangerAccessRequest request) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerDefaultPolicyEvaluator.isPolicyItemsMatch(" + request + ")");
        }

        boolean ret = false;

        if(CollectionUtils.isNotEmpty(policyItemEvaluators)) {
            for (RangerPolicyItemEvaluator policyItemEvaluator : policyItemEvaluators) {
                ret = policyItemEvaluator.isMatch(request);

                if(ret) {
                    break;
                }
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerDefaultPolicyEvaluator.isPolicyItemsMatch(" + request + "): " + ret);
        }

        return ret;
    }

	@Override
	public boolean isMatch(RangerAccessResource resource) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isMatch(" + resource + ")");
		}

		boolean ret = false;

		if(resourceMatcher != null) {
			ret = resourceMatcher.isMatch(resource);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isMatch(" + resource + "): " + ret);
		}

		return ret;
	}

	@Override
	public boolean isSingleAndExactMatch(RangerAccessResource resource) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isSingleAndExactMatch(" + resource + ")");
		}

		boolean ret = false;

		if(resourceMatcher != null) {
			ret = resourceMatcher.isSingleAndExactMatch(resource);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isSingleAndExactMatch(" + resource + "): " + ret);
		}

		return ret;
	}

	@Override
	public boolean isAccessAllowed(RangerAccessResource resource, String user, Set<String> userGroups, String accessType) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isAccessAllowed(" + resource + ", " + user + ", " + userGroups + ", " + accessType + ")");
		}

		boolean ret = isAccessAllowed(user, userGroups, accessType) && isMatch(resource);
		
		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isAccessAllowed(" + resource + ", " + user + ", " + userGroups + ", " + accessType + "): " + ret);
		}

		return ret;
	}

	@Override
	public boolean isAccessAllowed(Map<String, RangerPolicyResource> resources, String user, Set<String> userGroups, String accessType) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isAccessAllowed(" + resources + ", " + user + ", " + userGroups + ", " + accessType + ")");
		}

		boolean ret = isAccessAllowed(user, userGroups, accessType) && isMatch(resources);
		
		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isAccessAllowed(" + resources + ", " + user + ", " + userGroups + ", " + accessType + "): " + ret);
		}

		return ret;
	}


	protected boolean matchResourceHead(RangerAccessResource resource) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.matchResourceHead(" + resource + ")");
		}

		boolean ret = false;

		if(resourceMatcher != null) {
			ret = resourceMatcher.isHeadMatch(resource);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.matchResourceHead(" + resource + "): " + ret);
		}

		return ret;
	}

	protected boolean isMatch(Map<String, RangerPolicyResource> resources) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isMatch(" + resources + ")");
		}

		boolean ret = false;

		if(resourceMatcher != null) {
			ret = resourceMatcher.isMatch(resources);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isMatch(" + resources + "): " + ret);
		}

		return ret;
	}

	protected boolean isAccessAllowed(String user, Set<String> userGroups, String accessType) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultPolicyEvaluator.isAccessAllowed(" + user + ", " + userGroups + ", " + accessType + ")");
		}

		boolean ret = false;

		if(CollectionUtils.isNotEmpty(policyItemEvaluators)) {
	        for (RangerPolicyItemEvaluator policyItemEvaluator : policyItemEvaluators) {
	        	ret = policyItemEvaluator.matchUserGroup(user, userGroups) &&
	        		  policyItemEvaluator.matchAccessType(accessType);

	    		if(ret) {
	    			break;
	    		}
	        }
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultPolicyEvaluator.isAccessAllowed(" + user + ", " + userGroups + ", " + accessType + "): " + ret);
		}

		return ret;
	}

	public StringBuilder toString(StringBuilder sb) {
		sb.append("RangerDefaultPolicyEvaluator={");

		super.toString(sb);

		sb.append("resourceMatcher={");
		if(resourceMatcher != null) {
			resourceMatcher.toString(sb);
		}
		sb.append("} ");

		sb.append("}");

		return sb;
	}

	private void preprocessPolicy(RangerPolicy policy, RangerServiceDef serviceDef) {
		if(policy == null || CollectionUtils.isEmpty(policy.getPolicyItems()) || serviceDef == null) {
			return;
		}

		Map<String, Collection<String>> impliedAccessGrants = getImpliedAccessGrants(serviceDef);

		if(impliedAccessGrants == null || impliedAccessGrants.isEmpty()) {
			return;
		}

		for(RangerPolicyItem policyItem : policy.getPolicyItems()) {
			if(CollectionUtils.isEmpty(policyItem.getAccesses())) {
				continue;
			}

			// Only one round of 'expansion' is done; multi-level impliedGrants (like shown below) are not handled for now
			// multi-level impliedGrants: given admin=>write; write=>read: must imply admin=>read,write
			for(Map.Entry<String, Collection<String>> e : impliedAccessGrants.entrySet()) {
				String             accessType    = e.getKey();
				Collection<String> impliedGrants = e.getValue();

				RangerPolicyItemAccess access = getAccess(policyItem, accessType);

				if(access == null) {
					continue;
				}

				for(String impliedGrant : impliedGrants) {
					RangerPolicyItemAccess impliedAccess = getAccess(policyItem, impliedGrant);

					if(impliedAccess == null) {
						impliedAccess = new RangerPolicyItemAccess(impliedGrant, access.getIsAllowed());

						policyItem.getAccesses().add(impliedAccess);
					} else {
						if(! impliedAccess.getIsAllowed()) {
							impliedAccess.setIsAllowed(access.getIsAllowed());
						}
					}
				}
			}
		}
	}

	private Map<String, Collection<String>> getImpliedAccessGrants(RangerServiceDef serviceDef) {
		Map<String, Collection<String>> ret = null;

		if(serviceDef != null && !CollectionUtils.isEmpty(serviceDef.getAccessTypes())) {
			for(RangerAccessTypeDef accessTypeDef : serviceDef.getAccessTypes()) {
				if(!CollectionUtils.isEmpty(accessTypeDef.getImpliedGrants())) {
					if(ret == null) {
						ret = new HashMap<String, Collection<String>>();
					}

					Collection<String> impliedAccessGrants = ret.get(accessTypeDef.getName());

					if(impliedAccessGrants == null) {
						impliedAccessGrants = new HashSet<String>();

						ret.put(accessTypeDef.getName(), impliedAccessGrants);
					}

					for(String impliedAccessGrant : accessTypeDef.getImpliedGrants()) {
						impliedAccessGrants.add(impliedAccessGrant);
					}
				}
			}
		}

		return ret;
	}

	private RangerPolicyItemAccess getAccess(RangerPolicyItem policyItem, String accessType) {
		RangerPolicyItemAccess ret = null;

		if(policyItem != null && CollectionUtils.isNotEmpty(policyItem.getAccesses())) {
			for(RangerPolicyItemAccess itemAccess : policyItem.getAccesses()) {
				if(StringUtils.equalsIgnoreCase(itemAccess.getType(), accessType)) {
					ret = itemAccess;

					break;
				}
			}
		}

		return ret;
	}
}
