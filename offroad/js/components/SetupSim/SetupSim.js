import React, { Component } from 'react';
import {
    DeviceEventEmitter,
    View,
    Image,
} from 'react-native';
import { connect } from 'react-redux';
import PropTypes from 'prop-types';

import SimModule from '../../native/Sim';
import X from '../../themes';
import { BUTTON_CONTINUE_GRADIENT } from '../../styles/gradients';

import Styles from './SetupSimStyles';

class SetupSim extends Component {
    static propTypes = {
        onContinue: PropTypes.func,
    };

    constructor(props) {
        super(props);

        this.state = {
            simState: 'ABSENT',
            networkName: null,
        }
    }

    async componentWillMount() {
        const simState = await SimModule.getSimState();
        const { networkName } = await SimModule.getNetworkState();

        DeviceEventEmitter.addListener('onSimStateChange', this.onSimStateChange);
        DeviceEventEmitter.addListener('onCellularServiceStateChange', this.onCellularServiceStateChange);
        this.setState({ simState, networkName });
    }

    componentWillUnmount() {
        DeviceEventEmitter.removeListener('onSimStateChange', this.onSimStateChange);
        DeviceEventEmitter.removeListener('onCellularServiceStateChange', this.onCellularServiceStateChange);
    }

    onSimStateChange = ({ simState }) => {
        this.setState({ simState });
    }

    onCellularServiceStateChange = ({ networkName, isInService }) => {
        this.setState({ networkName });
    }

    render() {
        const { simState, networkName } = this.state;

        return (
            <View style={ Styles.root }>
                <View style={ Styles.content }>
                    <X.Text
                        color='white'
                        size='big'
                        weight='semibold'
                        style={ Styles.titleText }>
                        { simState === 'ABSENT' ? '没有检测到SIM卡' : '已插入SIM卡' }
                    </X.Text>
                    <X.Text
                        color='white'
                        size='small'
                        weight='light'
                        style={ Styles.detailText }>
                        { simState === 'ABSENT' ? '插入SIM卡来访问导航和音乐等服务。'
                        : (networkName === null ? '已插入SIM卡，但尚未发现移动网络。'
                                : "手机连接完成。完成设置继续。") }
                    </X.Text>
                </View>
                <View style={ Styles.statusRow }>
                    <View style={ Styles.status }>
                        <X.Text color='white'>
                            { simState === 'ABSENT' ? '等待SIM……' : (networkName === null ? '搜索蜂窝网络…' : `连接到 ${networkName}`)}
                        </X.Text>
                        { networkName !== null ?
                            <Image
                                source={ require('../../img/circled-checkmark.png') }
                                resizeMode='contain'
                                style={ Styles.checkmarkImage } />
                        : null }
                    </View>
                    <View style={ Styles.simImageContainer }>
                        <Image
                            source={ simState === 'ABSENT' ? require('../../img/illustration_sim_absent.png') : require('../../img/illustration_sim_present.png') }
                            style={ Styles.simImage }
                            resizeMode='contain'
                        />
                    </View>
                </View>
                <View style={ Styles.buttons }>
                    <View style={ Styles.skipButtonContainer }>
                        { networkName === null ?
                            <X.Button
                                onPress={ this.props.onContinue }
                                color='setupInverted'>
                                跳过
                            </X.Button>
                            : null
                        }
                    </View>
                    <View style={ Styles.buttonsRight }>
                        { simState !== 'ABSENT' ?
                            <X.Button
                                onPress={ this.props.onContinue }
                                style={ [ Styles.buttonRight ] }>
                                <X.Gradient
                                    colors={ networkName === null ? ['#8C959B', '#8C959B'] : BUTTON_CONTINUE_GRADIENT }
                                    style={ [ Styles.buttonRight ]  }>
                                    <X.Text
                                        color='white'
                                        weight='semibold'>
                                        { networkName === null ? '等待网络……' : '完成设置' }
                                    </X.Text>
                                </X.Gradient>
                            </X.Button>
                            :
                            <View style={ Styles.arrowImageContainer }>
                                <X.Gradient
                                    colors={ ['transparent', 'rgb(6,28,46)'] }
                                            style={ Styles.arrowImageGradient } />
                                <Image
                                    source={ require('../../img/illustration_arrow.png') }
                                    style={ Styles.arrowImage }
                                    resizeMode='contain' />
                            </View>
                        }
                    </View>
                </View>
            </View>
        );
    }
}

const mapStateToProps = state => ({
    hasSim: false,
    network: null, // Or string like 'T-Mobile'
});

export default connect(mapStateToProps)(SetupSim);
