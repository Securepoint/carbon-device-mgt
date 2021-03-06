/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.core.authorization;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.device.mgt.common.Device;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.common.EnrolmentInfo;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationException;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationService;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAuthorizationResult;
import org.wso2.carbon.device.mgt.common.group.mgt.DeviceGroup;
import org.wso2.carbon.device.mgt.common.group.mgt.GroupManagementException;
import org.wso2.carbon.device.mgt.common.permission.mgt.Permission;
import org.wso2.carbon.device.mgt.common.permission.mgt.PermissionManagementException;
import org.wso2.carbon.device.mgt.core.internal.DeviceManagementDataHolder;
import org.wso2.carbon.device.mgt.core.permission.mgt.PermissionUtils;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implementation of DeviceAccessAuthorization service.
 */
public class DeviceAccessAuthorizationServiceImpl implements DeviceAccessAuthorizationService {

    private final static String CDM_ADMIN_PERMISSION = "/device-mgt/admin";
    private static Log log = LogFactory.getLog(DeviceAccessAuthorizationServiceImpl.class);

    public DeviceAccessAuthorizationServiceImpl() {
        try {
            this.addAdminPermissionToRegistry();
        } catch (PermissionManagementException e) {
            log.error("Unable to add the emm-admin permission to the registry.", e);
        }
    }

    @Override
    public boolean isUserAuthorized(DeviceIdentifier deviceIdentifier, String username, String[] groupPermissions)
            throws DeviceAccessAuthorizationException {
        int tenantId = this.getTenantId();
        if (username == null || username.isEmpty()) {
            return false;
        }
        //check for admin and ownership permissions
        if (isAdminOrDeviceOwner(username, tenantId, deviceIdentifier)) {
            return true;
        }
        //check for group permissions
        try {
            if (groupPermissions == null || groupPermissions.length == 0) {
                return false;
            }
            for (String groupPermission : groupPermissions) {
                if (!isAuthorizedViaGroup(username, deviceIdentifier, groupPermission)) {
                    //if at least one fails, authorization fails
                    return false;
                }
            }
            return true;
        } catch (GroupManagementException | UserStoreException e) {
            throw new DeviceAccessAuthorizationException("Unable to authorize the access to device : " +
                                                         deviceIdentifier.getId() + " for the user : " +
                                                         username, e);
        }
    }

    @Override
    public boolean isUserAuthorized(DeviceIdentifier deviceIdentifier, String username)
            throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifier, username, null);
    }

    @Override
    public boolean isUserAuthorized(DeviceIdentifier deviceIdentifier, String[] groupPermissions)
            throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifier, this.getUserName(), groupPermissions);
    }

    @Override
    public boolean isUserAuthorized(DeviceIdentifier deviceIdentifier) throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifier, this.getUserName(), null);
    }

    @Override
    public DeviceAuthorizationResult isUserAuthorized(List<DeviceIdentifier> deviceIdentifiers, String username,
                                                      String[] groupPermissions)
            throws DeviceAccessAuthorizationException {
        int tenantId = this.getTenantId();
        if (username == null || username.isEmpty()) {
            return null;
        }
        DeviceAuthorizationResult deviceAuthorizationResult = new DeviceAuthorizationResult();
        for (DeviceIdentifier deviceIdentifier : deviceIdentifiers) {
            //check for admin and ownership permissions
            if (isAdminOrDeviceOwner(username, tenantId, deviceIdentifier)) {
                deviceAuthorizationResult.addAuthorizedDevice(deviceIdentifier);
            } else {
                try {
                    if (groupPermissions == null || groupPermissions.length == 0) {
                        return null;
                    }
                    //check for group permissions
                    boolean isAuthorized = true;
                    for (String groupPermission : groupPermissions) {
                        if (!isAuthorizedViaGroup(username, deviceIdentifier, groupPermission)) {
                            //if at least one failed, authorizations fails and break the loop
                            isAuthorized = false;
                            break;
                        }
                    }
                    if (isAuthorized) {
                        deviceAuthorizationResult.addAuthorizedDevice(deviceIdentifier);
                    } else {
                        deviceAuthorizationResult.addUnauthorizedDevice(deviceIdentifier);
                    }
                } catch (GroupManagementException | UserStoreException e) {
                    throw new DeviceAccessAuthorizationException("Unable to authorize the access to device : " +
                                                                 deviceIdentifier.getId() + " for the user : " +
                                                                 username, e);
                }
            }
        }
        return deviceAuthorizationResult;
    }

    @Override
    public DeviceAuthorizationResult isUserAuthorized(List<DeviceIdentifier> deviceIdentifiers, String username)
            throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifiers, username, null);
    }

    @Override
    public DeviceAuthorizationResult isUserAuthorized(List<DeviceIdentifier> deviceIdentifiers)
            throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifiers, this.getUserName(), null);
    }

    @Override
    public DeviceAuthorizationResult isUserAuthorized(List<DeviceIdentifier> deviceIdentifiers, String[] groupPermissions)
            throws DeviceAccessAuthorizationException {
        return isUserAuthorized(deviceIdentifiers, this.getUserName(), groupPermissions);
    }

    private boolean isAdminOrDeviceOwner(String username, int tenantId, DeviceIdentifier deviceIdentifier)
            throws DeviceAccessAuthorizationException {
        try {
            //First Check for admin users. If the user is an admin user we authorize the access to that device.
            //Secondly Check for device ownership. If the user is the owner of the device we allow the access.
            return (isAdminUser(username, tenantId) || isDeviceOwner(deviceIdentifier, username));
        } catch (UserStoreException e) {
            throw new DeviceAccessAuthorizationException("Unable to authorize the access to device : " +
                                                                 deviceIdentifier.getId() + " for the user : " +
                                                                 username, e);
        }
    }

    private boolean isAuthorizedViaGroup(String username, DeviceIdentifier deviceIdentifier, String groupPermission)
            throws GroupManagementException, UserStoreException {
        List<DeviceGroup> authorizedGroups =
                DeviceManagementDataHolder.getInstance().getGroupManagementProviderService()
                        .getGroups(username, groupPermission);
        List<DeviceGroup> groupsWithDevice =
                DeviceManagementDataHolder.getInstance().getGroupManagementProviderService()
                        .getGroups(deviceIdentifier);
        for (DeviceGroup group : authorizedGroups) {
            Iterator<DeviceGroup> groupsWithDeviceIterator = groupsWithDevice.iterator();
            while (groupsWithDeviceIterator.hasNext()) {
                DeviceGroup deviceGroup = groupsWithDeviceIterator.next();
                if (deviceGroup.getId() == group.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDeviceOwner(DeviceIdentifier deviceIdentifier, String username)
            throws DeviceAccessAuthorizationException {
        //Check for device ownership. If the user is the owner of the device we allow the access.
        try {
            Device device = DeviceManagementDataHolder.getInstance().getDeviceManagementProvider().
                    getDevice(deviceIdentifier);
            EnrolmentInfo enrolmentInfo = device.getEnrolmentInfo();
            if (enrolmentInfo != null && username.equalsIgnoreCase(enrolmentInfo.getOwner())) {
                return true;
            }
        } catch (DeviceManagementException e) {
            throw new DeviceAccessAuthorizationException("Unable to authorize the access to device : " +
                                                                 deviceIdentifier.getId() + " for the user : " +
                                                                 username, e);
        }
        return false;
    }

    private boolean isAdminUser(String username, int tenantId) throws UserStoreException {
        UserRealm userRealm = DeviceManagementDataHolder.getInstance().getRealmService().getTenantUserRealm(tenantId);
        if (userRealm != null && userRealm.getAuthorizationManager() != null) {
            return userRealm.getAuthorizationManager()
                    .isUserAuthorized(removeTenantDomain(username),
                                      PermissionUtils.getAbsolutePermissionPath(CDM_ADMIN_PERMISSION),
                                      PermissionMethod.UI_EXECUTE);
        }
        return false;
    }

    private String getUserName() {
        String username = CarbonContext.getThreadLocalCarbonContext().getUsername();
        if (username != null && !username.isEmpty()) {
            return removeTenantDomain(username);
        }
        return null;
    }

    private String removeTenantDomain(String username) {
        String tenantDomain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        if (username.endsWith(tenantDomain)) {
            return username.substring(0, username.lastIndexOf("@"));
        }
        return username;
    }

    private int getTenantId() {
        return CarbonContext.getThreadLocalCarbonContext().getTenantId();
    }

    private boolean addAdminPermissionToRegistry() throws PermissionManagementException {
        Permission permission = new Permission();
        permission.setPath(PermissionUtils.getAbsolutePermissionPath(CDM_ADMIN_PERMISSION));
        return PermissionUtils.putPermission(permission);
    }

    private Map<String, String> getOwnershipOfDevices(List<Device> devices) {
        Map<String, String> ownershipData = new HashMap<>();
        EnrolmentInfo enrolmentInfo;
        String owner;
        for (Device device : devices) {
            enrolmentInfo = device.getEnrolmentInfo();
            if (enrolmentInfo != null) {
                owner = enrolmentInfo.getOwner();
                if (owner != null && !owner.isEmpty()) {
                    ownershipData.put(device.getDeviceIdentifier(), owner);
                }
            }
        }
        return ownershipData;
    }

    public static final class PermissionMethod {
        public static final String READ = "read";
        public static final String WRITE = "write";
        public static final String DELETE = "delete";
        public static final String ACTION = "action";
        public static final String UI_EXECUTE = "ui.execute";

        private PermissionMethod() {
            throw new AssertionError();
        }
    }
}